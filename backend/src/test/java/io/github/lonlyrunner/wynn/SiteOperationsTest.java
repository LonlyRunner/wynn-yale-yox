package io.github.lonlyrunner.wynn;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:operations-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password="})
class SiteOperationsTest {
    @Autowired WebApplicationContext context;
    @Autowired SiteServicesRepository settings;
    @Autowired VisitorEventRepository visits;
    @Autowired JournalRepository journals;
    @Autowired PostRepository posts;
    @MockitoBean OssService oss;
    @MockitoBean CatChatService chat;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    final byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @BeforeEach void setup() {
        settings.deleteAll(); visits.deleteAll(); journals.deleteAll();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(oss.configured()).thenReturn(true);
        when(oss.presignedGetUrl(anyString())).thenAnswer(call -> "https://files.example.invalid/"+call.getArgument(0));
    }

    @Test void postListStaysSmallWhileArticleRetainsFullContent() throws Exception {
        String slug = "payload-" + UUID.randomUUID();
        Post post = new Post(slug, slug, slug, "TECH");
        post.contentZh = "文章完整正文";
        post.contentEn = "Full article";
        posts.save(post);
        var list = json.readTree(mvc.perform(get("/api/public/posts").param("q", slug))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, list.size());
        assertFalse(list.get(0).has("contentZh"));
        assertFalse(list.get(0).has("contentEn"));
        mvc.perform(get("/api/public/posts/" + slug))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentZh").value("文章完整正文"))
            .andExpect(jsonPath("$.contentEn").value("Full article"));
    }

    @Test void persistentIndependentSwitchesBlockNewRequestsWithoutBlockingHistory() throws Exception {
        mvc.perform(get("/api/public/services")).andExpect(jsonPath("$.aiCreationEnabled").value(true));
        mvc.perform(put("/api/admin/services").contentType("application/json").content("{\"aiCreationEnabled\":false,\"catChatEnabled\":false}"))
            .andExpect(status().is4xxClientError());
        switchServices(false,false);
        assertFalse(settings.findById(1L).orElseThrow().catChatEnabled);
        mvc.perform(get("/api/public/services")).andExpect(jsonPath("$.catChatEnabled").value(false));
        for (String type : List.of("IMAGE","VIDEO")) {
            mvc.perform(post("/api/ai/jobs").with(user("owner").roles("OWNER")).contentType("application/json")
                .content("{\"type\":\""+type+"\",\"prompt\":\"a kitten\"}"))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("SERVICE_DISABLED"));
        }
        mvc.perform(post("/api/chat/messages").contentType("application/json").content("{\"message\":\"hello\"}"))
            .andExpect(status().isLocked());
        mvc.perform(multipart("/api/chat/messages/multimodal").file(new MockMultipartFile("files","cat.png","image/png",png)))
            .andExpect(status().isLocked());
        verify(chat,never()).chat(any(),any(),any());
        mvc.perform(get("/api/chat/history").with(user("owner").roles("OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/ai/jobs").param("type","IMAGE").with(user("owner").roles("OWNER"))).andExpect(status().isOk());
        switchServices(false,true);
        when(chat.chat(any(),any(),any())).thenReturn(new ChatReply("hello","deepseek",List.of()));
        mvc.perform(post("/api/chat/messages").contentType("application/json").content("{\"message\":\"hello\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("hello"));
        mvc.perform(post("/api/ai/jobs").contentType("application/json").content("{\"type\":\"IMAGE\",\"prompt\":\"cat\"}"))
            .andExpect(status().isLocked());
        switchServices(true,false);
        mvc.perform(post("/api/ai/jobs").contentType("application/json").content("{\"type\":\"INVALID\",\"prompt\":\"cat\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/services").with(user("owner").roles("OWNER")).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test void trafficCountsPagesOnceAndDeduplicatesVisitorsWithoutCollectingPrivateUrls() throws Exception {
        String event=UUID.randomUUID().toString();
        var first=mvc.perform(post("/api/public/visits").contentType("application/json").header("User-Agent","iPhone Mobile")
            .content(visitBody(event,"/journal","https://search.example.invalid/?secret=private")))
            .andExpect(status().isNoContent()).andReturn();
        String header=first.getResponse().getHeader("Set-Cookie"); assertNotNull(header); assertTrue(header.contains("HttpOnly"));
        String visitor=header.split(";",2)[0].split("=",2)[1]; Cookie cookie=new Cookie("wynn_visitor",visitor);
        mvc.perform(post("/api/public/visits").cookie(cookie).contentType("application/json").content(visitBody(event,"/journal","")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/public/visits").cookie(cookie).contentType("application/json").content(visitBody(UUID.randomUUID().toString(),"/blog","")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/public/visits").contentType("application/json").content(visitBody(UUID.randomUUID().toString(),"/admin","")))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/public/visits").with(user("owner").roles("OWNER")).contentType("application/json").content(visitBody(UUID.randomUUID().toString(),"/","")))
            .andExpect(status().isNoContent());
        assertEquals(2,visits.count()); assertEquals("search.example.invalid",visits.findById(event).orElseThrow().source);
        mvc.perform(get("/api/admin/visitors")).andExpect(status().is4xxClientError());
        var analytics=mvc.perform(get("/api/admin/visitors").param("days","7").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalPv").value(2)).andExpect(jsonPath("$.totalUv").value(1))
            .andExpect(jsonPath("$.todayUv").value(1)).andExpect(jsonPath("$.trend.length()").value(7)).andReturn();
        String result=analytics.getResponse().getContentAsString(); assertFalse(result.contains(visitor)); assertFalse(result.contains("secret"));
    }

    @Test void journalPhotosAreValidatedPrivateInDraftsAndRemovedFromStorage() throws Exception {
        JournalEntry journal=new JournalEntry(); journal.titleZh="小事";journal.contentZh="今天的小事";journal.published=false;
        long id=journals.saveAndFlush(journal).id;
        mvc.perform(multipart("/api/admin/journals/"+id+"/images").file(new MockMultipartFile("files","cat.png","image/png",png)))
            .andExpect(status().is4xxClientError());
        mvc.perform(multipart("/api/admin/journals/"+id+"/images").with(user("owner").roles("OWNER"))
            .file(new MockMultipartFile("files","fake.png","image/png","not an image".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isBadRequest());
        verify(oss,never()).put(anyString(),any(),any());
        var upload=mvc.perform(multipart("/api/admin/journals/"+id+"/images").with(user("owner").roles("OWNER"))
            .file(new MockMultipartFile("files","../小猫.png","image/png",png))).andExpect(status().isOk()).andReturn();
        var image=json.readTree(upload.getResponse().getContentAsString()).get(0);String imageId=image.path("id").asText();
        assertEquals("小猫.png",image.path("name").asText());assertEquals(1,journals.findById(id).orElseThrow().images.size());
        mvc.perform(get("/api/public/journals")).andExpect(content().json("[]"));
        mvc.perform(get(image.path("previewUrl").asText())).andExpect(status().isNotFound());
        journal=journals.findById(id).orElseThrow();journal.published=true;journals.saveAndFlush(journal);
        mvc.perform(get("/api/public/journals")).andExpect(jsonPath("$[0].images.length()").value(1));
        mvc.perform(delete("/api/admin/journals/"+id+"/images/"+imageId).with(user("owner").roles("OWNER"))).andExpect(status().isNoContent());
        verify(oss).delete("journals/"+imageId);assertTrue(journals.findById(id).orElseThrow().images.isEmpty());
        mvc.perform(multipart("/api/admin/journals/"+id+"/images").with(user("owner").roles("OWNER"))
            .file(new MockMultipartFile("files","cat.png","image/png",png))).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/journals/"+id).with(user("owner").roles("OWNER"))).andExpect(status().isNoContent());
        assertFalse(journals.existsById(id));verify(oss,times(2)).delete(startsWith("journals/"));
    }

    @Test void failedImageStorageLeavesJournalAndCleansPartialUploads() throws Exception {
        JournalEntry entry=new JournalEntry();entry.titleZh="保留文字";entry.contentZh="图片失败也不能丢";
        long id=journals.saveAndFlush(entry).id;
        doThrow(new IllegalStateException("storage unavailable")).when(oss).put(startsWith("journals/"),any(),anyString());
        mvc.perform(multipart("/api/admin/journals/"+id+"/images").with(user("owner").roles("OWNER"))
            .file(new MockMultipartFile("files","cat.png","image/png",png))).andExpect(status().isBadGateway());
        assertTrue(journals.findById(id).orElseThrow().images.isEmpty());
        assertEquals("图片失败也不能丢",journals.findById(id).orElseThrow().contentZh);
        verify(oss).delete(startsWith("journals/"));
    }
    private void switchServices(boolean ai,boolean cat) throws Exception {
        mvc.perform(put("/api/admin/services").with(user("owner").roles("OWNER")).contentType("application/json")
            .content("{\"aiCreationEnabled\":"+ai+",\"catChatEnabled\":"+cat+"}")).andExpect(status().isOk());
    }
    private String visitBody(String event,String path,String referrer) throws Exception { return json.writeValueAsString(java.util.Map.of("eventId",event,"path",path,"referrer",referrer)); }
}
