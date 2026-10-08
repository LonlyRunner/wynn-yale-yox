package io.github.lonlyrunner.wynn;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostImageImportControllerTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==");

    @Test
    void uploadsReferencedImagesAndRewritesMarkdownLinks() throws Exception {
        var oss = mock(OssService.class);
        when(oss.configured()).thenReturn(true);
        var controller = new PostImageImportController(oss);
        String markdown = "# 标题\n\n![配图](langchain-images/01.png)\n\n```md\n![示例](langchain-images/not-uploaded.png)\n```\n";
        List<MultipartFile> files = List.of(
            new MockMultipartFile("files", "article.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8)),
            new MockMultipartFile("files", "01.png", "image/png", PNG));
        Map<String, Object> imported = controller.importFolder(files,
            List.of("guide/article.md", "guide/langchain-images/01.png"));

        assertEquals("article", imported.get("title"));
        assertEquals(1, imported.get("images"));
        String content = (String) imported.get("content");
        assertTrue(content.matches("(?s).*\\!\\[配图\\]\\(/api/public/post-images/[0-9a-f-]{36}\\.png\\).*"));
        assertTrue(content.contains("![示例](langchain-images/not-uploaded.png)"));
        verify(oss).put(eq("post-images/" + content.split("/api/public/post-images/")[1].split("\\)")[0]),
            eq(PNG), eq("image/png"));
    }

    @Test
    void reportsMissingImageBeforeUploadingAnything() {
        var oss = mock(OssService.class);
        var controller = new PostImageImportController(oss);
        var document = new MockMultipartFile("files", "article.md", "text/markdown",
            "![图](images/missing.png)".getBytes(StandardCharsets.UTF_8));
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.importFolder(List.of(document), List.of("article.md")));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(oss);
    }
}
