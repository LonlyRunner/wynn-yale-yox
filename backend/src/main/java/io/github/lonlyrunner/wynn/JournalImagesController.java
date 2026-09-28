package io.github.lonlyrunner.wynn;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class JournalImagesController {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private final JournalRepository journals;
    private final OssService oss;
    private final ImagePreviewService previews;
    private final Tika tika = new Tika();

    JournalImagesController(JournalRepository journals, OssService oss, ImagePreviewService previews) {
        this.journals = journals; this.oss = oss; this.previews = previews;
    }

    @PostMapping(value = "/api/admin/journals/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object upload(@PathVariable Long id, @RequestParam List<MultipartFile> files) throws IOException {
        JournalEntry entry = entry(id);
        if (files.isEmpty() || entry.images.size() + files.size() > 8) throw badRequest("每篇随心记最多添加 8 张图片");
        if (files.stream().anyMatch(file -> file.isEmpty() || file.getSize() > 5L * 1024 * 1024)
            || files.stream().mapToLong(MultipartFile::getSize).sum() > 16L * 1024 * 1024)
            throw badRequest("每张图片不超过 5 MB，本次上传总大小不超过 16 MB");
        List<byte[]> bytes = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (MultipartFile file : files) {
            byte[] data = file.getBytes(); String type = tika.detect(data);
            if (!TYPES.contains(type)) throw badRequest("仅支持 JPG、PNG、GIF、WebP 图片");
            bytes.add(data); types.add(type);
        }
        if (!oss.configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "图片存储服务尚未配置，文字已保存，请稍后添加图片");
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                String key = "journals/" + UUID.randomUUID(); keys.add(key);
                oss.put(key, bytes.get(i), types.get(i));
                String name = files.get(i).getOriginalFilename();
                name = name == null ? "图片" : name.replace('\\', '/').replaceAll("[\\p{Cntrl}]", "");
                name = name.substring(name.lastIndexOf('/') + 1);
                entry.images.add(new GuestbookAttachment(key, name.substring(0, Math.min(180, name.length())), types.get(i), bytes.get(i).length));
            }
            entry.updatedAt = Instant.now(); journals.saveAndFlush(entry);
        } catch (RuntimeException error) {
            for (String key : keys) {
                try { oss.delete(key); } catch (RuntimeException cleanup) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Journal image cleanup failed: {}", key); }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片未保存成功，文字仍保留，请重试上传", error);
        }
        return imageDtos(entry, oss);
    }

    @DeleteMapping("/api/admin/journals/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteImage(@PathVariable Long id, @PathVariable String imageId) {
        JournalEntry entry = entry(id);
        GuestbookAttachment image = image(entry, imageId);
        try { oss.delete(image.objectKey); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, "图片删除失败，请重试，随心记仍保留", error); }
        entry.images.remove(image);
        entry.updatedAt = Instant.now(); journals.saveAndFlush(entry);
    }

    @GetMapping(value = "/api/public/journals/{id}/images/{imageId}/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> preview(@PathVariable Long id, @PathVariable String imageId,
                                  @RequestParam(defaultValue = "480") int width, Authentication auth) {
        JournalEntry entry = entry(id);
        if (!entry.published && !AuthController.isOwner(auth)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        GuestbookAttachment image = image(entry, imageId);
        MediaItem item = new MediaItem(); item.id = -id; item.objectKey = image.objectKey;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
            .body(previews.preview(item, Math.max(1, Math.min(1200, width))));
    }

    static List<Map<String, Object>> imageDtos(JournalEntry entry, OssService oss) {
        return entry.images.stream().map(image -> {
            String imageId = image.objectKey.substring("journals/".length());
            return Map.<String, Object>of("id", imageId, "name", image.filename,
                "url", oss.configured() ? oss.presignedGetUrl(image.objectKey) : "",
                "previewUrl", "/api/public/journals/" + entry.id + "/images/" + imageId + "/preview");
        }).toList();
    }

    private JournalEntry entry(Long id) { return journals.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "随心记不存在")); }
    private GuestbookAttachment image(JournalEntry entry, String id) { return entry.images.stream().filter(image -> image.objectKey.equals("journals/" + id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason() == null ? "图片操作失败" : error.getReason())); }
}
