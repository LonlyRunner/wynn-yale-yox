package io.github.lonlyrunner.wynn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiModelSelectionTest {
    @Test
    void routesSelectedModelsAndOnlyFallsBackInAutomaticMode() throws Exception {
        // Local provider stub: no paid requests or external credentials.
        var json = new ObjectMapper();
        List<String> paths = new ArrayList<>();
        List<JsonNode> payloads = new ArrayList<>();
        var failRelay = new AtomicBoolean(false);
        var failDownload = new AtomicBoolean(false);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if ("POST".equals(exchange.getRequestMethod())) {
                paths.add(path);
                payloads.add(json.readTree(exchange.getRequestBody()));
                if (failRelay.get() && path.startsWith("/relay/")) { status = 503; body = "unavailable"; }
                else if (path.contains("/videos/")) body = "{\"id\":\"video-123\"}";
                else body = "{\"data\":[{\"url\":\"" + base + (failDownload.get() && path.startsWith("/relay/") ? "/missing.png" : "/result.png") + "\"}]}";
            } else { body = "local media bytes"; if (path.equals("/missing.png")) status = 404; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.endsWith(".png") ? "image/png" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var jobs = mock(AiJobRepository.class);
            var quotas = mock(GuestQuotaRepository.class);
            var generator = new AiGenerationService(jobs, mock(OssService.class));
            generator.relayBaseUrl = base + "/relay"; generator.relayApiKey = "local-test-key";
            generator.qwenBaseUrl = base + "/qwen"; generator.qwenApiKey = "local-test-key";
            generator.relayImageModel = "image-default";
            generator.relayImageModels = "image-default, image-quality, image-quality";
            generator.relayVideoModel = "video-default"; generator.relayVideoModels = "video-alternative";
            generator.qwenImageModel = "qwen-image";
            var controller = new AiController(jobs, quotas, generator);
            var owner = new UsernamePasswordAuthenticationToken("owner", "", List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();

            assertEquals(6, generator.models().size());
            assertFalse(json.writeValueAsString(controller.models()).contains("local-test-key"));
            for (String invalid : List.of("relay:video-default", "relay:unknown", "qwen:video-default")) {
                assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.create(
                    new AiJobRequest("IMAGE", "test", "16:9", "720p", 5, invalid), null, request, response)).getStatusCode().value());
            }
            verifyNoInteractions(jobs, quotas); // Invalid models never reserve a guest allowance or create a job.
            when(jobs.save(any(AiJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

            for (String model : List.of("image-default", "image-quality")) {
                var dto = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "16:9", "720p", 5, "relay:" + model), owner, request, response);
                assertEquals("COMPLETED", dto.get("status")); assertEquals(model, dto.get("model"));
                assertEquals(model, payloads.get(payloads.size() - 1).path("model").asText());
                assertEquals("/relay/v1/images/generations", paths.get(paths.size() - 1));
            }
            var directQwen = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "16:9", "720p", 5, "qwen:qwen-image"), owner, request, response);
            assertEquals("qwen-image", directQwen.get("model"));
            assertEquals("/qwen/v1/images/generations", paths.get(paths.size() - 1));
            assertEquals("1664x928", payloads.get(payloads.size() - 1).path("size").asText());

            failRelay.set(true);
            int before = paths.size();
            var failed = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "1:1", "720p", 5, "relay:image-quality"), owner, request, response);
            assertEquals("FAILED", failed.get("status")); assertEquals("image-quality", failed.get("model"));
            assertEquals(before + 1, paths.size()); // Explicit selection must not silently change models.
            var automatic = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "1:1", "720p", 5, null), owner, request, response);
            assertEquals("COMPLETED", automatic.get("status")); assertEquals("qwen-fallback", automatic.get("provider"));
            assertEquals("qwen-image", automatic.get("model")); assertEquals(before + 3, paths.size());

            failRelay.set(false);
            failDownload.set(true);
            var downloadFailed = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "1:1", "720p", 5, "relay:image-default"), owner, request, response);
            assertEquals("FAILED", downloadFailed.get("status"));
            assertEquals("image-default", downloadFailed.get("model"));
            var downloadFallback = (Map<?, ?>) controller.create(new AiJobRequest("IMAGE", "test", "1:1", "720p", 5, null), owner, request, response);
            assertEquals("COMPLETED", downloadFallback.get("status"));
            assertEquals("qwen-fallback", downloadFallback.get("provider"));
            failDownload.set(false);
            for (String model : List.of("video-default", "video-alternative")) {
                var dto = (Map<?, ?>) controller.create(new AiJobRequest("VIDEO", "test", "9:16", "480p", 6, "relay:" + model), owner, request, response);
                assertEquals("PROCESSING", dto.get("status")); assertEquals(model, dto.get("model"));
                var payload = payloads.get(payloads.size() - 1);
                assertEquals(model, payload.path("model").asText()); assertEquals(6, payload.path("duration").asInt());
                assertEquals("9:16", payload.path("aspect_ratio").asText());
                assertEquals("/relay/v1/videos/generations", paths.get(paths.size() - 1));
            }
            generator.relayApiKey = "";
            assertTrue(generator.models().stream().noneMatch(model -> model.type().equals("VIDEO")));
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> generator.selectModel("VIDEO", null)).getStatusCode().value());
        } finally { server.stop(0); }
    }

    @Test
    void savesBase64ImageBytesWithoutDownloadingAndRejectsInvalidData() throws Exception {
        var json = new ObjectMapper();
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRksAAAAASUVORK5CYII=";
        var encoded = new AtomicReference<>(png);
        var payload = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            payload.set(json.readTree(exchange.getRequestBody()));
            byte[] body = json.writeValueAsBytes(Map.of("data", List.of(Map.of("b64_json", encoded.get()))));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var jobs = mock(AiJobRepository.class);
            when(jobs.save(any(AiJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
            var oss = mock(OssService.class);
            when(oss.configured()).thenReturn(true);
            var generator = new AiGenerationService(jobs, oss);
            generator.relayBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            generator.relayApiKey = "local-test-key";
            var job = new AiJob();
            job.type = "IMAGE"; job.provider = "relay"; job.model = "grok-imagine-image-2.0";
            job.prompt = "test"; job.aspectRatio = "16:9";
            generator.createImage(job);
            assertEquals("b64_json", payload.get().path("response_format").asText());
            assertEquals("16:9", payload.get().path("aspect_ratio").asText());
            assertEquals("COMPLETED", job.status);
            assertTrue(job.resultObjectKey.startsWith("ai-generated/images/"));
            assertTrue(job.resultObjectKey.endsWith(".png"));
            assertNull(job.resultUrl);
            verify(oss).put(eq(job.resultObjectKey), aryEq(Base64.getDecoder().decode(png)), eq("image/png"));
            clearInvocations(oss);
            for (String invalid : List.of("invalid base64!", Base64.getEncoder().encodeToString("<html>error</html>".getBytes(StandardCharsets.UTF_8)))) {
                encoded.set(invalid);
                var badJob = new AiJob();
                badJob.type = "IMAGE"; badJob.provider = "relay"; badJob.model = job.model; badJob.prompt = "test";
                generator.createImage(badJob);
                assertEquals("FAILED", badJob.status);
                assertNotNull(badJob.errorMessage);
                assertNull(badJob.resultObjectKey);
            }
            verify(oss, never()).put(anyString(), any(byte[].class), anyString());
        } finally { server.stop(0); }
    }
}
