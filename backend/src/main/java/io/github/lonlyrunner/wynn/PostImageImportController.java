package io.github.lonlyrunner.wynn;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.Tika;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PostImageImportController {
    private static final Pattern IMAGE_LINK = Pattern.compile("!\\[[^\\]]*\\]\\((?:<([^>]+)>|([^\\s)]+))(?:\\s+[\\\"'][^\\\"']*[\\\"'])?\\)");
    private static final Pattern IMAGE_NAME = Pattern.compile("[0-9a-f-]{36}\\.(?:png|jpg|gif|webp)");
    private static final long MAX_MARKDOWN = 2L * 1024 * 1024;
    private static final long MAX_IMAGE = 5L * 1024 * 1024;
    private final OssService oss;
    private final Tika tika = new Tika();

    PostImageImportController(OssService oss) { this.oss = oss; }

    @PostMapping(value = "/api/admin/posts/import-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> importFolder(@RequestParam("files") List<MultipartFile> files,
                                     @RequestParam("paths") List<String> paths) throws IOException {
        if (files.size() != paths.size() || files.isEmpty() || files.size() > 80)
            throw badRequest("请选择包含一篇 Markdown 和图片的文件夹，最多 80 个文件");
        Map<String, MultipartFile> byPath = new LinkedHashMap<>();
        MultipartFile document = null;
        String documentPath = null;
        for (int i = 0; i < files.size(); i++) {
            String path = normalize(paths.get(i));
            if (byPath.putIfAbsent(path, files.get(i)) != null) throw badRequest("文件夹里存在同名文件：" + path);
            if (path.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown)")) {
                if (document != null) throw badRequest("一个文件夹只能包含一篇 Markdown 文章");
                document = files.get(i); documentPath = path;
            }
        }
        if (document == null || document.isEmpty() || document.getSize() > MAX_MARKDOWN)
            throw badRequest("请选择不超过 2 MB 的 Markdown 文章");
        String content = new String(document.getBytes(), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        String base = documentPath.contains("/") ? documentPath.substring(0, documentPath.lastIndexOf('/') + 1) : "";
        Set<String> references = new LinkedHashSet<>();
        rewrite(content, base, Map.of(), references);
        Map<String, ImageUpload> images = new LinkedHashMap<>();
        for (String reference : references) {
            MultipartFile file = byPath.get(reference);
            if (file == null) throw badRequest("缺少文章引用的图片：" + reference);
            if (file.isEmpty() || file.getSize() > MAX_IMAGE) throw badRequest("图片不能为空，且每张不超过 5 MB：" + reference);
            byte[] bytes = file.getBytes();
            String type = tika.detect(bytes);
            String extension = switch (type) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                default -> throw badRequest("仅支持 PNG、JPG、GIF、WebP 图片：" + reference);
            };
            images.put(reference, new ImageUpload(bytes, type, extension));
        }
        if (!images.isEmpty() && !oss.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储服务尚未配置");
        Map<String, String> urls = new LinkedHashMap<>();
        List<String> uploaded = new ArrayList<>();
        try {
            for (var entry : images.entrySet()) {
                ImageUpload image = entry.getValue();
                String name = UUID.randomUUID() + "." + image.extension();
                String key = "post-images/" + name;
                oss.put(key, image.bytes(), image.type());
                uploaded.add(key);
                urls.put(entry.getKey(), "/api/public/post-images/" + name);
            }
        } catch (RuntimeException error) {
            for (String key : uploaded) {
                try { oss.delete(key); }
                catch (RuntimeException ignored) { /* A failed cleanup must not hide the upload error. */ }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片上传失败，请重试", error);
        }
        // ponytail: abandoned drafts leave uploaded images; add post-owned cleanup if this becomes common.
        String title = documentPath.substring(documentPath.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
        return Map.of("title", title, "content", rewrite(content, base, urls, new LinkedHashSet<>()), "images", urls.size());
    }

    @GetMapping("/api/public/post-images/{name}")
    ResponseEntity<byte[]> image(@PathVariable String name) {
        if (!IMAGE_NAME.matcher(name).matches()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String type = name.endsWith(".jpg") ? "image/jpeg" : "image/" + name.substring(name.lastIndexOf('.') + 1);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .contentType(MediaType.parseMediaType(type)).body(oss.get("post-images/" + name));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在", error);
        }
    }

    private String rewrite(String content, String base, Map<String, String> urls, Set<String> references) {
        StringBuilder result = new StringBuilder();
        boolean fenced = false;
        for (String line : content.split("(?<=\\n)", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) fenced = !fenced;
            if (fenced || trimmed.startsWith("```") || trimmed.startsWith("~~~")) { result.append(line); continue; }
            Matcher matcher = IMAGE_LINK.matcher(line);
            int cursor = 0;
            while (matcher.find()) {
                int group = matcher.group(1) == null ? 2 : 1;
                String path = resolve(base, matcher.group(group));
                if (path == null) continue;
                references.add(path);
                String url = urls.get(path);
                if (url == null) continue;
                result.append(line, cursor, matcher.start(group)).append(url);
                cursor = matcher.end(group);
            }
            result.append(line, cursor, line.length());
        }
        return result.toString();
    }

    private String resolve(String base, String raw) {
        if (raw.startsWith("/") || raw.startsWith("#") || raw.matches("(?i)^(https?:|data:).*")) return null;
        String decoded;
        try { decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw badRequest("图片路径编码无效：" + raw); }
        decoded = decoded.split("[?#]", 2)[0];
        return normalize(base + decoded);
    }

    private String normalize(String raw) {
        String path = raw.replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.contains(":")) throw badRequest("图片路径无效：" + raw);
        try {
            Path clean = Path.of(path.replace('/', File.separatorChar)).normalize();
            if (clean.isAbsolute() || clean.startsWith("..")) throw badRequest("图片路径不能超出所选文件夹：" + raw);
            return clean.toString().replace(File.separatorChar, '/');
        } catch (java.nio.file.InvalidPathException error) {
            throw badRequest("图片路径无效：" + raw);
        }
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record ImageUpload(byte[] bytes, String type, String extension) {}
}
