package io.github.lonlyrunner.wynn;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostSaveTest {
    @Test
    void duplicateSlugReturnsConflictButEditingOriginalStillWorks() {
        var posts = mock(PostRepository.class);
        var existing = new Post("11", "旧文章", "Old post", "TECH");
        existing.id = 1L;
        when(posts.findBySlug("11")).thenReturn(Optional.of(existing));
        when(posts.findById(1L)).thenReturn(Optional.of(existing));
        when(posts.save(existing)).thenReturn(existing);
        var controller = new AdminController(null, posts, null, null, null, null, null, null);
        var request = new PostRequest("11", "新标题", "New title", "TECH", "", "", "", "# 正文", "", "", true);

        var error = assertThrows(ResponseStatusException.class, () -> controller.createPost(request));
        assertEquals(409, error.getStatusCode().value());
        controller.updatePost(1L, request);
        verify(posts).save(existing);
    }
}
