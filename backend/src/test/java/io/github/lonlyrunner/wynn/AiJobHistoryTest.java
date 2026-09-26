package io.github.lonlyrunner.wynn;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-history-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "app.oss.endpoint="
})
class AiJobHistoryTest {
    @Autowired AiController controller;
    @Autowired AiJobRepository jobs;

    @Test
    void restoresCompletedResultsWithoutExposingAnotherGuestsHistory() {
        jobs.deleteAll();
        for (int i = 0; i < 21; i++) save("IMAGE", "guest-a", i);
        AiJob other = save("IMAGE", "guest-b", 30);
        AiJob video = save("VIDEO", "guest-a", 31);
        var guest = new MockHttpServletRequest();
        guest.setCookies(new Cookie("wynn_guest", "guest-a"));
        var response = new MockHttpServletResponse();

        var images = controller.recentJobs("image", null, guest, response);
        assertEquals(20, images.size());
        assertEquals("result-20", images.get(0).get("prompt"));
        assertEquals("COMPLETED", images.get(0).get("status"));
        assertEquals("https://example.invalid/result-20.jpg", images.get(0).get("resultUrl"));
        assertEquals(images.get(0).get("resultUrl"), images.get(0).get("downloadUrl"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(images.stream().noneMatch(item -> item.get("id").equals(other.id)));
        assertEquals(video.id, controller.recentJobs("VIDEO", null, guest, response).get(0).get("id"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> controller.job(other.id, null, guest, response)).getStatusCode().value());

        var owner = new UsernamePasswordAuthenticationToken("owner", "", List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        assertEquals(other.id, controller.recentJobs("IMAGE", owner, guest, response).get(0).get("id"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> controller.recentJobs("unknown", null, guest, response)).getStatusCode().value());
    }

    private AiJob save(String type, String guest, int order) {
        AiJob job = new AiJob("test", type, "result-" + order, guest, "1:1", "480p", 1);
        job.status = "COMPLETED";
        job.resultUrl = "https://example.invalid/result-" + order + ".jpg";
        job.createdAt = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(order);
        return jobs.save(job);
    }
}
