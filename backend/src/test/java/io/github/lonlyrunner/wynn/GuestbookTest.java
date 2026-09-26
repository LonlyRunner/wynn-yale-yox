package io.github.lonlyrunner.wynn;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:guestbook-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password="
})
class GuestbookTest {
    @Autowired WebApplicationContext context;
    @Autowired GuestbookRepository entries;
    @MockitoBean OssService oss;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    final byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @BeforeEach void setup() {
        entries.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(oss.configured()).thenReturn(true);
        when(oss.presignedGetUrl(anyString())).thenAnswer(call -> "https://files.example.invalid/" + call.getArgument(0));
        when(oss.presignedDownloadUrl(anyString(), anyString())).thenAnswer(call -> "https://files.example.invalid/download/" + call.getArgument(0));
    }

    @Test void visitorsPublishAttachmentsButOnlyOwnerSeesContactsAndDeletes() throws Exception {
        var session = new MockHttpSession();
        var result = mvc.perform(multipart("/api/public/guestbook").session(session)
            .file(new MockMultipartFile("files", "截图.png", "image/png", png))
            .file(new MockMultipartFile("files", "../反馈.txt", "text/plain", "反馈详情".getBytes(StandardCharsets.UTF_8)))
            .param("author", "小伙伴").param("content", "<script>alert(1)</script>\n喜欢这个网站")
            .param("contact", "private-contact@example.invalid"))
            .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertEquals(1, entries.count());
        var entry = entries.findById(id).orElseThrow();
        assertEquals(2, entry.attachments.size());
        assertEquals("反馈.txt", entry.attachments.get(1).filename);
        assertEquals("image/png", entry.attachments.get(0).contentType);
        assertEquals("application/octet-stream", entry.attachments.get(1).contentType);
        var publicResponse = mvc.perform(get("/api/public/guestbook"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String body = publicResponse.getResponse().getContentAsString();
        assertFalse(body.contains("private-contact")); assertFalse(body.contains("\"contact\""));
        var item = json.readTree(body).path("items").get(0);
        assertTrue(item.path("attachments").get(0).path("image").asBoolean());
        assertEquals("", item.path("attachments").get(1).path("url").asText());
        assertTrue(item.path("attachments").get(1).path("downloadUrl").asText().contains("download"));
        mvc.perform(multipart("/api/public/guestbook").session(session).param("content", "double click"))
            .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/admin/guestbook")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/admin/guestbook/" + id)).andExpect(status().is4xxClientError());
        verify(oss, never()).delete(anyString());
        var admin = mvc.perform(get("/api/admin/guestbook").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk()).andReturn();
        assertTrue(admin.getResponse().getContentAsString().contains("private-contact@example.invalid"));
        mvc.perform(delete("/api/admin/guestbook/" + id).with(user("owner").roles("OWNER"))).andExpect(status().isNoContent());
        assertEquals(0, entries.count());
        for (var attachment : entry.attachments) verify(oss).delete(attachment.objectKey);
    }

    @Test void validatesUploadsCleansFailedWritesAndPaginates() throws Exception {
        mvc.perform(multipart("/api/public/guestbook").param("content", "   ")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/public/guestbook").param("author", "a".repeat(81)).param("content", "test"))
            .andExpect(status().isBadRequest());
        var tooMany = multipart("/api/public/guestbook");
        for (int i = 0; i < 5; i++) tooMany.file(new MockMultipartFile("files", "test.txt", "text/plain", new byte[]{1}));
        mvc.perform(tooMany).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/public/guestbook").file(new MockMultipartFile("files", "large.bin", "application/octet-stream", new byte[5 * 1024 * 1024 + 1])))
            .andExpect(status().isBadRequest());
        verify(oss, never()).put(anyString(), any(), anyString());
        var attempts = new AtomicInteger();
        doAnswer(call -> { if (attempts.incrementAndGet() == 2) throw new IllegalStateException("storage unavailable"); return null; })
            .when(oss).put(anyString(), any(), anyString());
        mvc.perform(multipart("/api/public/guestbook")
            .file(new MockMultipartFile("files", "first.txt", "text/plain", new byte[]{1}))
            .file(new MockMultipartFile("files", "second.txt", "text/plain", new byte[]{2})))
            .andExpect(status().isBadGateway());
        assertEquals(0, entries.count()); verify(oss, times(2)).delete(anyString());
        when(oss.configured()).thenReturn(false);
        mvc.perform(multipart("/api/public/guestbook").param("content", "文字不需要附件存储"))
            .andExpect(status().isCreated());
        for (int i = 0; i < 12; i++) {
            var entry = new GuestbookEntry(); entry.author = "guest"; entry.content = "note " + i; entries.save(entry);
        }
        var first = mvc.perform(get("/api/public/guestbook")).andReturn();
        var page = json.readTree(first.getResponse().getContentAsString());
        assertEquals(12, page.path("items").size()); assertTrue(page.path("hasMore").asBoolean()); assertEquals(13, page.path("total").asInt());
        var second = mvc.perform(get("/api/public/guestbook?page=1")).andReturn();
        assertEquals(1, json.readTree(second.getResponse().getContentAsString()).path("items").size());
    }
}
