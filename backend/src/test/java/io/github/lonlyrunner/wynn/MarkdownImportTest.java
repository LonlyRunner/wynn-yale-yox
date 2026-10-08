package io.github.lonlyrunner.wynn;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownImportTest {
    @Test
    void preservesMarkdownSourceForBlogArticles() {
        var controller = new AdminController(null, null, null, null, null, null, null, null);
        String markdown = "# 标题\n\n- 列表项\n\n```java\nSystem.out.println(\"hello\");\n```\n\n| 列 | 值 |\n| --- | --- |\n| A | B |\n";
        for (String filename : new String[] { "article.MD", "article.markdown" }) {
            var file = new MockMultipartFile("file", filename, "text/markdown",
                ("\uFEFF" + markdown).getBytes(StandardCharsets.UTF_8));
            var imported = (Map<?, ?>) controller.importDocument(file);
            assertEquals("article", imported.get("title"));
            assertEquals(markdown, imported.get("content"));
        }
    }
}
