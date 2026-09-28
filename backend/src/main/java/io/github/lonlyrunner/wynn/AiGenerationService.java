package io.github.lonlyrunner.wynn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class AiGenerationService {
    private final AiJobRepository jobs;
    private final OssService oss;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    @Value("${app.ai.relay.base-url:}") String relayBaseUrl;
    @Value("${app.ai.relay.api-key:}") String relayApiKey;
    @Value("${app.ai.relay.image-model:grok-imagine-image-2.0}") String relayImageModel;
    @Value("${app.ai.relay.video-model:grok-imagine-video-1.5}") String relayVideoModel;
    @Value("${app.ai.relay.image-models:}") String relayImageModels;
    @Value("${app.ai.relay.video-models:}") String relayVideoModels;
    @Value("${app.ai.qwen.base-url:}") String qwenBaseUrl;
    @Value("${app.ai.qwen.api-key:}") String qwenApiKey;
    @Value("${app.ai.qwen.image-model:qwen-image-3.0}") String qwenImageModel;
    @Value("${app.ai.qwen.vision-model:qwen3-vl-flash}") String qwenVisionModel;

    AiGenerationService(AiJobRepository jobs, OssService oss) {
        this.jobs = jobs;
        this.oss = oss;
    }

    record GenerationModel(String id, String name, String type, String model, String provider) {}

    List<GenerationModel> models() {
        List<GenerationModel> result = new ArrayList<>();
        if (configured(relayBaseUrl, relayApiKey) || configured(qwenBaseUrl, qwenApiKey))
            result.add(new GenerationModel("auto", "自动选择（失败时回退千问）", "IMAGE", "", "auto"));
        if (configured(relayBaseUrl, relayApiKey)) {
            addModels(result, "IMAGE", relayImageModel, relayImageModels);
            addModels(result, "VIDEO", relayVideoModel, relayVideoModels);
        }
        if (configured(qwenBaseUrl, qwenApiKey))
            result.add(new GenerationModel("qwen:" + qwenImageModel, "千问 · " + qwenImageModel, "IMAGE", qwenImageModel, "qwen"));
        return result;
    }

    GenerationModel selectModel(String type, String id) {
        List<GenerationModel> available = models().stream().filter(item -> item.type().equals(type)).toList();
        if (available.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "该类型的生成模型尚未配置");
        if (id == null || id.isBlank()) return available.get(0); // Existing clients retain automatic/default selection.
        return available.stream().filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选模型不可用或不支持该创作类型，请刷新模型列表"));
    }

    private void addModels(List<GenerationModel> result, String type, String primary, String alternatives) {
        Arrays.stream((primary + "," + (alternatives == null ? "" : alternatives)).split(","))
            .map(String::trim).filter(model -> !model.isEmpty()).distinct()
            .forEach(model -> result.add(new GenerationModel("relay:" + model, model, type, model, "relay")));
    }

    AiJob createImage(AiJob job) {
        boolean automatic = "auto".equals(job.provider);
        if (automatic) {
            boolean relay = configured(relayBaseUrl, relayApiKey);
            job.provider = relay ? "relay" : "qwen";
            job.model = relay ? relayImageModel : qwenImageModel;
        }
        job.status = "GENERATING";
        jobs.save(job);
        try {
            try {
                requestAndStoreImage(job, "qwen".equals(job.provider));
            } catch (Exception relayFailure) {
                if (!automatic || !"relay".equals(job.provider) || !configured(qwenBaseUrl, qwenApiKey)) throw relayFailure;
                job.provider = "qwen-fallback";
                job.model = qwenImageModel;
                requestAndStoreImage(job, true);
            }
            job.status = "COMPLETED";
            job.errorMessage = null;
        } catch (Exception error) {
            job.status = "FAILED";
            job.errorMessage = safeMessage(error);
        }
        job.updatedAt = Instant.now();
        return jobs.save(job);
    }

    AiJob createVideo(AiJob job) {
        job.status = "SUBMITTING";
        jobs.save(job);
        try {
            requireConfig(relayBaseUrl, relayApiKey, "Relay");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", job.model);
            payload.put("prompt", job.prompt);
            payload.put("duration", job.duration == null ? 5 : job.duration);
            payload.put("resolution", job.resolution == null ? "720p" : job.resolution);
            payload.put("aspect_ratio", job.aspectRatio == null ? "16:9" : job.aspectRatio);
            JsonNode response = postJson(apiUrl(relayBaseUrl, "/videos/generations"), relayApiKey, payload);
            job.remoteId = firstText(response, "request_id", "id");
            if (job.remoteId == null || job.remoteId.isBlank()) throw new IllegalStateException("视频服务没有返回任务编号");
            job.provider = "relay";
            job.status = "PROCESSING";
            job.errorMessage = null;
        } catch (Exception error) {
            job.status = "FAILED";
            job.errorMessage = safeMessage(error);
        }
        job.updatedAt = Instant.now();
        return jobs.save(job);
    }

    AiJob refresh(AiJob job) {
        if (!"VIDEO".equals(job.type) || !"PROCESSING".equals(job.status) || job.remoteId == null) return job;
        try {
            JsonNode response = getJson(apiUrl(relayBaseUrl, "/videos/" + job.remoteId), relayApiKey);
            String state = firstText(response, "status", "state");
            String normalized = state == null ? "" : state.toLowerCase();
            if (normalized.contains("fail") || normalized.contains("error") || normalized.contains("cancel")) {
                job.status = "FAILED";
                job.errorMessage = firstText(response, "error", "message", "detail");
            } else if (normalized.contains("complete") || normalized.contains("success") || normalized.contains("done")) {
                String url = firstMediaUrl(response);
                if (url != null) storeRemoteMedia(job, url, "video/mp4", "mp4");
                else storeVideoContent(job);
                job.status = "COMPLETED";
                job.errorMessage = null;
            }
        } catch (Exception error) {
            job.errorMessage = safeMessage(error);
        }
        job.updatedAt = Instant.now();
        return jobs.save(job);
    }

    String resultUrl(AiJob job) {
        if (job.resultObjectKey != null && oss.configured()) return oss.presignedGetUrl(job.resultObjectKey);
        return job.resultUrl;
    }

    String downloadUrl(AiJob job) {
        if (job.resultObjectKey == null || !oss.configured()) return job.resultUrl;
        String extension = job.resultObjectKey.contains(".") ? job.resultObjectKey.substring(job.resultObjectKey.lastIndexOf('.') + 1) : ("VIDEO".equals(job.type) ? "mp4" : "jpg");
        return oss.presignedDownloadUrl(job.resultObjectKey, "wynn-ai-" + job.id + "." + extension);
    }

    Map<String, String> describeImage(String imageUrl, String title) throws Exception {
        requireConfig(qwenBaseUrl, qwenApiKey, "Qwen Vision");
        Map<String, Object> image = Map.of("type", "image_url", "image_url", Map.of("url", imageUrl));
        Map<String, Object> instruction = Map.of("type", "text", "text",
            "Analyze this image for a reusable image-generation prompt. Return JSON only with promptZh and promptEn. " +
            "Describe subject, composition, lighting, palette, atmosphere, lens or illustration style, and key details. " +
            "Do not identify real people and do not add markdown. Suggested title: " + (title == null ? "" : title));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", qwenVisionModel);
        payload.put("messages", List.of(Map.of("role", "user", "content", List.of(image, instruction))));
        payload.put("temperature", 0.2);
        JsonNode response = postJson(apiUrl(qwenBaseUrl, "/chat/completions"), qwenApiKey, payload);
        String content = response.path("choices").path(0).path("message").path("content").asText();
        int start = content.indexOf('{'), end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("视觉模型没有返回有效提示词");
        JsonNode prompts = json.readTree(content.substring(start, end + 1));
        String zh = prompts.path("promptZh").asText();
        String en = prompts.path("promptEn").asText();
        if (zh.isBlank() || en.isBlank()) throw new IllegalStateException("视觉模型返回的提示词不完整");
        return Map.of("promptZh", zh, "promptEn", en);
    }

    private void requestAndStoreImage(AiJob job, boolean qwen) throws Exception {
        String baseUrl = qwen ? qwenBaseUrl : relayBaseUrl;
        String apiKey = qwen ? qwenApiKey : relayApiKey;
        requireConfig(baseUrl, apiKey, qwen ? "Qwen" : "Relay");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", job.model);
        payload.put("prompt", job.prompt);
        payload.put("n", 1);
        // Receiving Grok's image bytes avoids a second request to its temporary image host.
        payload.put("response_format", !qwen && job.model.startsWith("grok-imagine-image") && oss.configured() ? "b64_json" : "url");
        if (qwen) payload.put("size", qwenSize(job.aspectRatio));
        else payload.put("aspect_ratio", job.aspectRatio == null ? "1:1" : job.aspectRatio);
        JsonNode response = postJson(apiUrl(baseUrl, "/images/generations"), apiKey, payload);
        String encoded = response.path("data").path(0).path("b64_json").asText("");
        if (!encoded.isBlank()) {
            if (encoded.length() > 40 * 1024 * 1024) throw new IllegalStateException("生成图片超过 30MB 限制");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("图片服务返回了无效的 Base64 数据"); }
            String contentType = new Tika().detect(bytes);
            if (!List.of("image/jpeg", "image/png", "image/webp").contains(contentType))
                throw new IllegalStateException("图片服务返回的数据不是有效的图片格式");
            storeInOss(job, bytes, contentType, "jpg");
            return;
        }
        String url = firstMediaUrl(response);
        if (url == null) throw new IllegalStateException("图片服务没有返回图片数据或结果地址");
        storeRemoteMedia(job, url, "image/jpeg", "jpg");
    }

    private JsonNode postJson(String url, String apiKey, Object payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(4))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI 服务返回 HTTP " + response.statusCode() + ": " + compactError(response.body()));
        }
        return json.readTree(response.body());
    }

    private JsonNode getJson(String url, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(40))
            .header("Authorization", "Bearer " + apiKey)
            .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI 服务返回 HTTP " + response.statusCode() + ": " + compactError(response.body()));
        }
        return json.readTree(response.body());
    }

    private void storeVideoContent(AiJob job) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl(relayBaseUrl, "/videos/" + job.remoteId + "/content")))
            .timeout(Duration.ofMinutes(3))
            .header("Authorization", "Bearer " + relayApiKey)
            .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("视频文件暂未就绪");
        String key = "ai-generated/videos/" + UUID.randomUUID() + ".mp4";
        oss.put(key, response.body(), "video/mp4");
        job.resultObjectKey = key;
        job.resultUrl = null;
    }

    private void storeRemoteMedia(AiJob job, String url, String fallbackType, String fallbackExtension) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("生成结果下载失败");
        String contentType = response.headers().firstValue("Content-Type").orElse(fallbackType).split(";")[0];
        if (oss.configured()) {
            storeInOss(job, response.body(), contentType, fallbackExtension);
        } else {
            job.resultUrl = url;
        }
    }

    private void storeInOss(AiJob job, byte[] bytes, String contentType, String fallbackExtension) {
        if (!oss.configured()) throw new IllegalStateException("请配置 OSS 后再保存生成图片数据");
        String folder = "VIDEO".equals(job.type) ? "videos" : "images";
        String key = "ai-generated/" + folder + "/" + UUID.randomUUID() + "." + extension(contentType, fallbackExtension);
        oss.put(key, bytes, contentType);
        job.resultObjectKey = key;
        job.resultUrl = null;
    }

    private String firstMediaUrl(JsonNode node) {
        for (String field : new String[]{"url", "video_url", "output_url"}) {
            for (JsonNode value : node.findValues(field)) {
                if (value.isTextual() && value.asText().startsWith("http")) return value.asText();
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.findValue(field);
            if (value != null) {
                if (value.isTextual()) return value.asText();
                if (value.isNumber()) return value.asText();
                if ("error".equals(field) && value.has("message")) return value.path("message").asText();
            }
        }
        return null;
    }

    private String apiUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (!base.endsWith("/v1")) base += "/v1";
        return base + path;
    }

    private String qwenSize(String ratio) {
        if ("16:9".equals(ratio)) return "1664x928";
        if ("9:16".equals(ratio)) return "928x1664";
        if ("4:3".equals(ratio)) return "1472x1104";
        if ("3:4".equals(ratio)) return "1104x1472";
        return "1024x1024";
    }

    private String extension(String contentType, String fallback) {
        if (contentType.contains("png")) return "png";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("mp4")) return "mp4";
        return fallback;
    }

    private String compactError(String body) {
        if (body == null) return "unknown error";
        String value = body.replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "生成失败，请稍后重试" : message.substring(0, Math.min(message.length(), 800));
    }

    private void requireConfig(String baseUrl, String apiKey, String name) {
        if (!configured(baseUrl, apiKey)) throw new IllegalStateException(name + " 尚未配置");
    }

    private boolean configured(String baseUrl, String apiKey) {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
