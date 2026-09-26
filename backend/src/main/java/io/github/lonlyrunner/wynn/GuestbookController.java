package io.github.lonlyrunner.wynn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class GuestbookController {
    private static final long FILE_LIMIT = 5L * 1024 * 1024, TOTAL_LIMIT = 16L * 1024 * 1024;
    private static final Set<String> PREVIEW_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private final GuestbookRepository entries;
    private final OssService oss;
    private final Tika tika = new Tika();

    GuestbookController(GuestbookRepository entries, OssService oss) { this.entries = entries; this.oss = oss; }

    @GetMapping("/api/public/guestbook")
    Object list(@RequestParam(defaultValue = "0") int page, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return page(page, false);
    }

    @GetMapping("/api/admin/guestbook")
    Object adminList(@RequestParam(defaultValue = "0") int page, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return page(page, true);
    }

    @PostMapping(value = "/api/public/guestbook", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestParam(defaultValue = "") String author, @RequestParam(defaultValue = "") String content,
                  @RequestParam(defaultValue = "") String contact, @RequestParam(required = false) List<MultipartFile> files,
                  HttpServletRequest request) {
        String name = bounded(author, 80), message = bounded(content, 3000), replyTo = bounded(contact, 200);
        List<MultipartFile> uploads = files == null ? List.of() : files;
        if (message.isBlank() && uploads.isEmpty()) throw badRequest("写点文字或添加图片、文件后再发布吧");
        if (uploads.size() > 4) throw badRequest("每条留言最多添加 4 个附件");
        if (uploads.stream().anyMatch(file -> file.isEmpty() || file.getSize() > FILE_LIMIT)) throw badRequest("附件不能为空，且每个不能超过 5 MB");
        if (uploads.stream().mapToLong(MultipartFile::getSize).sum() > TOTAL_LIMIT) throw badRequest("附件总大小不能超过 16 MB");
        if (!uploads.isEmpty() && !oss.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "附件服务暂不可用，可以先发送文字留言");

        var session = request.getSession(true);
        synchronized (session) {
            Long last = (Long) session.getAttribute("guestbook-last-post");
            if (last != null && System.currentTimeMillis() - last < 15000)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "发布太快啦，请稍等 15 秒再留言");
            GuestbookEntry entry = new GuestbookEntry();
            entry.author = name.isBlank() ? "路过的小伙伴" : name;
            entry.content = message; entry.contact = replyTo;
            List<String> uploadedKeys = new ArrayList<>();
            try {
                for (MultipartFile file : uploads) {
                    byte[] bytes = file.getBytes();
                    String detected = tika.detect(bytes);
                    // Only raster images render inline; all other uploads are forced downloads.
                    String type = PREVIEW_TYPES.contains(detected) ? detected : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                    String key = "guestbook/" + UUID.randomUUID();
                    uploadedKeys.add(key);
                    oss.put(key, bytes, type);
                    entry.attachments.add(new GuestbookAttachment(key, filename(file), type, bytes.length));
                }
                entries.saveAndFlush(entry);
            } catch (IOException | RuntimeException failure) {
                for (String key : uploadedKeys) {
                    try { oss.delete(key); }
                    catch (RuntimeException cleanupFailure) { LoggerFactory.getLogger(getClass()).warn("Guestbook attachment cleanup failed for {}", key); }
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "留言未保存成功，请稍后重试", failure);
            }
            session.setAttribute("guestbook-last-post", System.currentTimeMillis());
            return Map.of("id", entry.id);
        }
    }

    @DeleteMapping("/api/admin/guestbook/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        GuestbookEntry entry = entries.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "留言已不存在"));
        try {
            for (GuestbookAttachment attachment : entry.attachments) oss.delete(attachment.objectKey);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "附件删除失败，请重试，留言记录仍然保留", failure);
        }
        entries.delete(entry);
    }

    private Object page(int requestedPage, boolean admin) {
        int page = Math.max(0, Math.min(requestedPage, 100000));
        var result = entries.findAll(PageRequest.of(page, 12, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return Map.of("items", result.getContent().stream().map(entry -> dto(entry, admin)).toList(),
            "page", page, "hasMore", result.hasNext(), "total", result.getTotalElements());
    }

    private Map<String, Object> dto(GuestbookEntry entry, boolean admin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.id); result.put("author", entry.author); result.put("content", entry.content);
        result.put("createdAt", entry.createdAt);
        if (admin) result.put("contact", entry.contact == null ? "" : entry.contact);
        result.put("attachments", entry.attachments.stream().map(file -> Map.of(
            "name", file.filename, "size", file.size, "image", PREVIEW_TYPES.contains(file.contentType),
            "url", PREVIEW_TYPES.contains(file.contentType) && oss.configured() ? oss.presignedGetUrl(file.objectKey) : "",
            "downloadUrl", oss.configured() ? oss.presignedDownloadUrl(file.objectKey, file.filename) : ""
        )).toList());
        return result;
    }

    private String bounded(String value, int maximum) {
        String result = value == null ? "" : value.trim();
        if (result.length() > maximum) throw badRequest("昵称、留言或联系方式超过长度限制");
        return result;
    }

    private String filename(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "attachment";
        return name.substring(0, Math.min(180, name.length()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason() == null ? "请求失败" : error.getReason()));
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
