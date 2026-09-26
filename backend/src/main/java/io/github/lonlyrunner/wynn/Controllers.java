package io.github.lonlyrunner.wynn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.tika.Tika;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

record LoginRequest(@NotBlank String username, @NotBlank String password) {}
record AiJobRequest(@NotBlank String type, @NotBlank @Size(max = 4000) String prompt, String aspectRatio, String resolution, Integer duration, @Size(max = 200) String model) {}
record CommentRequest(@NotBlank @Size(max = 80) String author, @Size(max = 160) String email, @NotBlank @Size(max = 2000) String content) {}
record PostRequest(@NotBlank String slug, @NotBlank String titleZh, @NotBlank String titleEn, String category, String tags, String summaryZh, String summaryEn, String contentZh, String contentEn, String coverObjectKey, boolean published) {}
record MediaRequest(@NotBlank String objectKey, String titleZh, String titleEn, String mediaType, String promptZh, String promptEn, int sortOrder) {}
record ChatRequest(String model, @NotBlank @Size(max = 2000) String message, List<ChatTurn> history) {}
record DrawingRequest(String model) {}
record KnowledgeRequest(@NotBlank @Size(max = 200) String title, @Size(max = 500) String tags, String kind, @NotBlank @Size(max = 30000) String content, boolean enabled) {}
record JournalRequest(@NotBlank @Size(max = 200) String titleZh, @Size(max = 200) String titleEn, @NotBlank @Size(max = 30000) String contentZh, @Size(max = 30000) String contentEn, @Size(max = 40) String mood, java.time.LocalDate happenedAt, boolean published) {}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationManager manager;

    AuthController(AuthenticationManager manager) { this.manager = manager; }

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        Authentication auth = manager.authenticate(new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return Map.of("authenticated", true, "username", auth.getName());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> invalidCredentials() { return Map.of("error", "INVALID_CREDENTIALS"); }

    @PostMapping("/logout")
    void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication auth) {
        return Map.of("authenticated", isOwner(auth), "username", isOwner(auth) ? auth.getName() : "");
    }

    static boolean isOwner(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }
}

@RestController
@RequestMapping("/api/public")
class PublicController {
    private final PostRepository posts;
    private final CommentRepository comments;
    private final MediaRepository media;
    private final JournalRepository journals;
    private final OssService oss;
    private final ImagePreviewService previews;

    PublicController(PostRepository posts, CommentRepository comments, MediaRepository media, JournalRepository journals, OssService oss, ImagePreviewService previews) {
        this.posts = posts;
        this.comments = comments;
        this.media = media;
        this.journals = journals;
        this.oss = oss;
        this.previews = previews;
    }

    @GetMapping("/profile")
    Object profile() {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("displayName", "Lonely__Runner"); profile.put("realName", "Wang Yuan");
        profile.put("cityZh", "洛阳"); profile.put("cityEn", "Luoyang"); profile.put("email", "wyy048003@gamil.com");
        profile.put("github", "https://github.com/LonlyRunner"); profile.put("gitee", "https://gitee.com/q7531");
        profile.put("xianyu", "https://m.tb.cn/h.8uafqmy?tk=UTlBT9DjXYY"); profile.put("wechat", "WangYuan_0425_Taurus");
        profile.put("bioZh", "全栈开发者与 AI 应用实践者，专注 Java、Spring 与智能产品，也用影像记录技术之外的灵感。");
        profile.put("bioEn", "Full-stack developer and applied AI builder focused on Java, Spring, intelligent products, and visual stories beyond code.");
        return profile;
    }

    @GetMapping("/posts")
    Object posts(@RequestParam(defaultValue = "") String q) {
        List<Post> result = q.isBlank() ? posts.findByPublishedTrueOrderByCreatedAtDesc() : posts.searchPublished(q.trim());
        return result.stream().map(this::postDto).toList();
    }

    @GetMapping("/posts/{slug}")
    Object post(@PathVariable String slug) {
        return postDto(posts.findBySlugAndPublishedTrue(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/posts/{slug}/comments")
    Object comments(@PathVariable String slug) {
        Post post = posts.findBySlugAndPublishedTrue(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return comments.findByPostIdAndApprovedTrueOrderByCreatedAtAsc(post.id).stream().map(c -> Map.of(
            "id", c.id, "author", c.author, "content", c.content, "createdAt", c.createdAt
        )).toList();
    }

    @PostMapping("/posts/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    Object comment(@PathVariable String slug, @Valid @RequestBody CommentRequest request) {
        Post post = posts.findBySlugAndPublishedTrue(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Comment saved = comments.save(new Comment(post, request.author().trim(), request.email(), request.content().trim()));
        return Map.of("id", saved.id, "status", "PENDING_REVIEW");
    }

    @GetMapping("/media")
    Object media() {
        return media.findAllByOrderBySortOrderAscCreatedAtDesc().stream().map(this::mediaDto).toList();
    }

    @GetMapping("/journals")
    Object journals() {
        return journals.findByPublishedTrueOrderByHappenedAtDescCreatedAtDesc().stream().map(this::journalDto).toList();
    }

    @GetMapping(value = "/media/{id}/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> mediaPreview(@PathVariable Long id, @RequestParam(defaultValue = "960") int width) {
        MediaItem item = media.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .contentType(MediaType.IMAGE_JPEG)
                .body(previews.preview(item, Math.max(1, Math.min(width, 1200))));
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "图片预览生成失败", error);
        }
    }

    @GetMapping(value = "/rss.xml", produces = "application/rss+xml;charset=UTF-8")
    ResponseEntity<String> rss(HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getServerName() + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Wynn Yale Yox</title><link>")
            .append(base).append("</link><description>技术与思考</description>");
        for (Post post : posts.findByPublishedTrueOrderByCreatedAtDesc()) {
            xml.append("<item><title>").append(escapeXml(post.titleZh)).append("</title><link>")
                .append(base).append("/blog/").append(URLEncoder.encode(post.slug, StandardCharsets.UTF_8))
                .append("</link><description>").append(escapeXml(value(post.summaryZh))).append("</description><pubDate>")
                .append(post.createdAt).append("</pubDate></item>");
        }
        xml.append("</channel></rss>");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/rss+xml;charset=UTF-8")).body(xml.toString());
    }

    private Map<String, Object> postDto(Post post) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", post.id); dto.put("slug", post.slug); dto.put("titleZh", value(post.titleZh)); dto.put("titleEn", value(post.titleEn));
        dto.put("category", value(post.category)); dto.put("tags", value(post.tags)); dto.put("summaryZh", value(post.summaryZh)); dto.put("summaryEn", value(post.summaryEn));
        dto.put("contentZh", value(post.contentZh)); dto.put("contentEn", value(post.contentEn)); dto.put("published", post.published); dto.put("createdAt", post.createdAt);
        dto.put("coverUrl", signed(post.coverObjectKey));
        return dto;
    }

    private Map<String, Object> mediaDto(MediaItem item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.id); dto.put("objectKey", item.objectKey); dto.put("titleZh", value(item.titleZh)); dto.put("titleEn", value(item.titleEn));
        dto.put("mediaType", value(item.mediaType)); dto.put("promptZh", value(item.promptZh)); dto.put("promptEn", value(item.promptEn));
        dto.put("sortOrder", item.sortOrder); dto.put("url", signed(item.objectKey));
        return dto;
    }

    private Map<String, Object> journalDto(JournalEntry item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.id); dto.put("titleZh", value(item.titleZh)); dto.put("titleEn", value(item.titleEn));
        dto.put("contentZh", value(item.contentZh)); dto.put("contentEn", value(item.contentEn)); dto.put("mood", value(item.mood));
        dto.put("happenedAt", item.happenedAt); dto.put("createdAt", item.createdAt);
        return dto;
    }

    private String signed(String key) { return key == null || key.isBlank() || !oss.configured() ? "" : oss.presignedGetUrl(key); }
    private String value(String value) { return value == null ? "" : value; }
    private String escapeXml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}

@RestController
@RequestMapping("/api/chat")
class ChatController {
    private final CatChatService chat;
    private final ChatHistoryRepository history;
    private final AiGenerationService generator;
    private final ObjectMapper json = new ObjectMapper();
    private final Tika tika = new Tika();

    ChatController(CatChatService chat, ChatHistoryRepository history, AiGenerationService generator) {
        this.chat = chat; this.history = history; this.generator = generator;
    }

    @GetMapping("/models")
    Object models() { return chat.models(); }

    @PostMapping("/messages")
    Object message(@Valid @RequestBody ChatRequest request, Authentication auth) {
        return reply(request.model(), request.message(), request.history() == null ? List.of() : request.history(), auth);
    }

    @PostMapping(value = "/messages/multimodal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object multimodal(@RequestParam(defaultValue = "deepseek") String model,
                      @RequestParam(defaultValue = "") String message,
                      @RequestParam(defaultValue = "[]") String historyJson,
                      @RequestPart(name = "files", required = false) List<MultipartFile> files,
                      Authentication auth) {
        try {
            List<ChatTurn> turns = json.readValue(historyJson, new TypeReference<List<ChatTurn>>() {});
            StringBuilder enriched = new StringBuilder(message.isBlank() ? "请分析我发送的附件。" : message.trim());
            List<MultipartFile> attachments = files == null ? List.of() : files.stream().filter(file -> !file.isEmpty()).limit(5).toList();
            for (MultipartFile file : attachments) enrich(enriched, model, file);
            return reply(model, enriched.toString(), turns, auth);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件解析失败：" + Objects.requireNonNullElse(error.getMessage(), "未知错误"), error);
        }
    }

    @GetMapping("/history")
    Object history(Authentication auth) {
        requireOwner(auth);
        return history.findAllByOrderByCreatedAtAsc().stream().skip(Math.max(0, history.count() - 100)).map(item -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", item.id); dto.put("role", item.role); dto.put("content", item.content); dto.put("model", Objects.requireNonNullElse(item.model, "")); dto.put("createdAt", item.createdAt);
            try { dto.put("sources", item.sourcesJson == null || item.sourcesJson.isBlank() ? List.of() : json.readValue(item.sourcesJson, new TypeReference<List<Map<String, String>>>() {})); }
            catch (Exception ignored) { dto.put("sources", List.of()); }
            return dto;
        }).toList();
    }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearHistory(Authentication auth) { requireOwner(auth); history.deleteAllInBatch(); }

    private Object reply(String model, String message, List<ChatTurn> turns, Authentication auth) {
        try {
            ChatReply result = chat.chat(model, message, turns);
            if (AuthController.isOwner(auth)) {
                history.save(new ChatHistoryEntry("user", message, model, null));
                history.save(new ChatHistoryEntry("assistant", result.answer(), result.model(), json.writeValueAsString(result.sources())));
            }
            return result;
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, error.getMessage(), error);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "聊天记录保存失败", error);
        }
    }

    private void enrich(StringBuilder message, String model, MultipartFile file) throws Exception {
        if (file.getSize() > 12 * 1024 * 1024L) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单个附件不能超过 12MB");
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "附件").replaceAll("[\\r\\n]", "_");
        String type = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");
        if (type.startsWith("image/")) {
            String dataUrl = "data:" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, String> prompts = generator.describeImage(dataUrl, name);
            message.append("\n\n【图片附件：").append(name).append("】\n视觉特征：").append(prompts.get("promptZh"));
        } else if (type.startsWith("audio/")) {
            String transcript = chat.transcribe(model, file);
            message.append("\n\n【语音附件：").append(name).append("】\n")
                .append(transcript.isBlank() ? "当前模型未能转写这段语音，请结合用户文字回答，并说明需要补充文字内容。" : "语音转写：" + transcript);
        } else {
            String content = tika.parseToString(file.getInputStream());
            content = content == null ? "" : content.strip();
            if (content.length() > 10000) content = content.substring(0, 10000) + "…";
            message.append("\n\n【文件附件：").append(name).append("】\n").append(content.isBlank() ? "文件中没有提取到可读文字。" : content);
        }
    }

    private void requireOwner(Authentication auth) {
        if (!AuthController.isOwner(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @PostMapping("/drawings/pelican")
    Object drawPelican(@RequestBody DrawingRequest request) {
        try {
            return chat.drawPelican(request.model());
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", error.getMessage()));
        }
    }
}

@RestController
@RequestMapping("/api/ai")
class AiController {
    private static final String GUEST_COOKIE = "wynn_guest";
    private final AiJobRepository jobs;
    private final GuestQuotaRepository quotas;
    private final AiGenerationService generator;

    AiController(AiJobRepository jobs, GuestQuotaRepository quotas, AiGenerationService generator) {
        this.jobs = jobs;
        this.quotas = quotas;
        this.generator = generator;
    }

    @GetMapping("/quota")
    @Transactional
    Object quota(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        if (AuthController.isOwner(auth)) return Map.of("owner", true, "imageRemaining", true, "videoRemaining", true);
        String guestId = guestId(request, response);
        GuestQuota quota = quotas.findByGuestId(guestId).orElseGet(() -> quotas.save(new GuestQuota(guestId)));
        return Map.of("owner", false, "imageRemaining", !quota.imageUsed, "videoRemaining", !quota.videoUsed);
    }

    @GetMapping("/models")
    Object models() { return generator.models(); }

    @GetMapping("/jobs")
    List<Map<String, Object>> recentJobs(@RequestParam(defaultValue = "IMAGE") String type, Authentication auth,
                                        HttpServletRequest request, HttpServletResponse response) {
        String normalized = type.toUpperCase(Locale.ROOT);
        if (!List.of("IMAGE", "VIDEO").contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的创作类型");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        List<AiJob> recent = AuthController.isOwner(auth)
            ? jobs.findTop20ByTypeOrderByCreatedAtDesc(normalized)
            : jobs.findTop20ByGuestIdAndTypeOrderByCreatedAtDesc(guestId(request, response), normalized);
        return recent.stream().map(this::jobDto).toList();
    }

    @PostMapping("/jobs")
    @Transactional
    Object create(@Valid @RequestBody AiJobRequest requestBody, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        String type = requestBody.type().toUpperCase(Locale.ROOT);
        if (!List.of("IMAGE", "VIDEO").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的创作类型");
        AiGenerationService.GenerationModel selected = generator.selectModel(type, requestBody.model());
        boolean owner = AuthController.isOwner(auth);
        String guestId = owner ? null : guestId(request, response);
        if (!owner) reserve(guestId, type);
        int duration = requestBody.duration() == null ? 5 : Math.max(1, Math.min(15, requestBody.duration()));
        String requestedResolution = requestBody.resolution();
        String resolution = requestedResolution != null && List.of("480p", "720p", "1080p").contains(requestedResolution) ? requestedResolution : "720p";
        String ratio = requestBody.aspectRatio() == null || requestBody.aspectRatio().isBlank() ? "16:9" : requestBody.aspectRatio();
        AiJob job = new AiJob(selected.provider(), type, requestBody.prompt().trim(), guestId, ratio, resolution, duration);
        job.model = selected.model();
        jobs.save(job);
        AiJob result = "IMAGE".equals(type) ? generator.createImage(job) : generator.createVideo(job);
        if (!owner && "FAILED".equals(result.status)) release(guestId, type);
        return jobDto(result);
    }

    @GetMapping("/jobs/{id}")
    Object job(@PathVariable Long id, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        AiJob job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!AuthController.isOwner(auth)) {
            String guestId = guestId(request, response);
            if (!Objects.equals(guestId, job.guestId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return jobDto(generator.refresh(job));
    }

    private synchronized void reserve(String guestId, String type) {
        GuestQuota quota = quotas.findByGuestId(guestId).orElseGet(() -> quotas.save(new GuestQuota(guestId)));
        if ("IMAGE".equals(type) && quota.imageUsed) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "访客图片试用次数已用完，请登录管理员账号继续使用");
        if ("VIDEO".equals(type) && quota.videoUsed) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "访客视频试用次数已用完，请登录管理员账号继续使用");
        if ("IMAGE".equals(type)) quota.imageUsed = true; else quota.videoUsed = true;
        quotas.save(quota);
    }

    private synchronized void release(String guestId, String type) {
        quotas.findByGuestId(guestId).ifPresent(quota -> {
            if ("IMAGE".equals(type)) quota.imageUsed = false; else quota.videoUsed = false;
            quotas.save(quota);
        });
    }

    private String guestId(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (GUEST_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        String id = UUID.randomUUID().toString();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(GUEST_COOKIE, id).httpOnly(true).sameSite("Lax").path("/").maxAge(31536000).build().toString());
        return id;
    }

    private Map<String, Object> jobDto(AiJob job) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", job.id); dto.put("provider", job.provider); dto.put("type", job.type); dto.put("prompt", job.prompt); dto.put("status", job.status);
        dto.put("model", job.model);
        dto.put("aspectRatio", job.aspectRatio); dto.put("resolution", job.resolution); dto.put("duration", job.duration); dto.put("resultUrl", generator.resultUrl(job));
        dto.put("downloadUrl", generator.downloadUrl(job));
        dto.put("error", job.errorMessage == null ? "" : job.errorMessage); dto.put("createdAt", job.createdAt);
        return dto;
    }
}

@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final AiJobRepository jobs;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final MediaRepository media;
    private final KnowledgeRepository knowledge;
    private final JournalRepository journals;
    private final OssService oss;
    private final AiGenerationService generator;
    private final Tika tika = new Tika();

    AdminController(AiJobRepository jobs, PostRepository posts, CommentRepository comments, MediaRepository media, KnowledgeRepository knowledge, JournalRepository journals, OssService oss, AiGenerationService generator) {
        this.jobs = jobs; this.posts = posts; this.comments = comments; this.media = media; this.knowledge = knowledge; this.journals = journals; this.oss = oss; this.generator = generator;
    }

    @GetMapping("/ai/jobs")
    Object jobs() { return jobs.findAllByOrderByCreatedAtDesc().stream().map(job -> adminJobDto(generator.refresh(job))).toList(); }

    @DeleteMapping("/ai/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteJob(@PathVariable Long id) {
        AiJob job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (job.resultObjectKey != null && media.findByObjectKey(job.resultObjectKey).isEmpty() && oss.configured()) oss.delete(job.resultObjectKey);
        jobs.delete(job);
    }

    @PostMapping("/ai/jobs/{id}/gallery")
    Object addJobToGallery(@PathVariable Long id) {
        AiJob job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"IMAGE".equals(job.type) || !"COMPLETED".equals(job.status) || job.resultObjectKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有已完成并保存到 OSS 的图片可以加入影像库");
        }
        MediaItem item = media.findByObjectKey(job.resultObjectKey).orElseGet(() -> {
            String title = job.prompt == null || job.prompt.isBlank() ? "AI 创作" : (job.prompt.length() > 80 ? job.prompt.substring(0, 80) + "…" : job.prompt);
            MediaItem created = new MediaItem(job.resultObjectKey, title, title, "IMAGE", (int) media.count());
            created.promptZh = job.prompt; created.promptEn = job.prompt;
            return created;
        });
        return Map.of("id", media.save(item).id, "status", "SAVED");
    }

    @PostMapping("/oss/upload-url")
    Object uploadUrl(@RequestBody Map<String, String> body) {
        return Map.of("url", oss.presignedPutUrl(body.getOrDefault("objectKey", "uploads/file.bin"), body.get("contentType")));
    }

    @PostMapping("/media")
    Object media(@Valid @RequestBody MediaRequest request) {
        MediaItem item = media.findByObjectKey(request.objectKey()).orElseGet(() -> new MediaItem(request.objectKey(), request.titleZh(), request.titleEn(), request.mediaType(), request.sortOrder()));
        item.titleZh = request.titleZh(); item.titleEn = request.titleEn(); item.mediaType = request.mediaType(); item.sortOrder = request.sortOrder();
        item.promptZh = request.promptZh(); item.promptEn = request.promptEn();
        ensurePrompt(item);
        return Map.of("id", media.save(item).id, "status", "SAVED");
    }

    @PostMapping(value = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object uploadMedia(@RequestParam MultipartFile file, @RequestParam(defaultValue = "0") int sortOrder) throws Exception {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择需要上传的图片");
        String contentType = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");
        if (!contentType.startsWith("image/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持图片文件");
        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "image");
        String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "-");
        String objectKey = "gallery/" + System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeName;
        oss.put(objectKey, file.getBytes(), contentType);
        String title = originalName.replaceFirst("\\.[^.]+$", "");
        MediaItem item = new MediaItem(objectKey, title, title, "IMAGE", sortOrder);
        ensurePrompt(item);
        return Map.of("id", media.save(item).id, "status", "SAVED", "objectKey", objectKey);
    }

    @DeleteMapping("/media/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMedia(@PathVariable Long id) {
        MediaItem item = media.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (oss.configured()) oss.delete(item.objectKey);
        for (AiJob job : jobs.findByResultObjectKey(item.objectKey)) { job.resultObjectKey = null; jobs.save(job); }
        media.delete(item);
    }

    @PostMapping("/media/prompts/backfill")
    Object backfillPrompts() {
        int updated = 0;
        for (MediaItem item : media.findAll()) {
            if ((item.promptZh == null || item.promptZh.isBlank()) && "IMAGE".equalsIgnoreCase(item.mediaType)) {
                ensurePrompt(item);
                media.save(item);
                updated++;
            }
        }
        return Map.of("updated", updated);
    }

    @GetMapping("/posts")
    Object posts() { return posts.findAll().stream().map(this::postDto).toList(); }

    @PostMapping("/posts")
    Object createPost(@Valid @RequestBody PostRequest request) { return postDto(savePost(new Post(), request)); }

    @PutMapping("/posts/{id}")
    Object updatePost(@PathVariable Long id, @Valid @RequestBody PostRequest request) {
        return postDto(savePost(posts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)), request));
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePost(@PathVariable Long id) { posts.deleteById(id); }

    @PostMapping(value = "/posts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object importDocument(@RequestPart("file") MultipartFile file) {
        try {
            String content = tika.parseToString(file.getInputStream());
            String filename = file.getOriginalFilename() == null ? "Imported document" : file.getOriginalFilename();
            String title = filename.replaceFirst("\\.[^.]+$", "");
            return Map.of("title", title, "content", content.trim());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档解析失败", error);
        }
    }

    @GetMapping("/comments")
    Object comments() { return comments.findAll().stream().map(c -> Map.of("id", c.id, "postId", c.post.id, "postTitle", c.post.titleZh, "author", c.author, "email", c.email == null ? "" : c.email, "content", c.content, "approved", c.approved, "createdAt", c.createdAt)).toList(); }

    @PostMapping("/comments/{id}/approve")
    Object approve(@PathVariable Long id) {
        Comment comment = comments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        comment.approved = true;
        comments.save(comment);
        return Map.of("status", "APPROVED");
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@PathVariable Long id) { comments.deleteById(id); }

    @GetMapping("/knowledge")
    Object knowledge() { return knowledge.findAll().stream().map(this::knowledgeDto).toList(); }

    @PostMapping("/knowledge")
    Object createKnowledge(@Valid @RequestBody KnowledgeRequest request) { return knowledgeDto(saveKnowledge(new KnowledgeEntry(), request)); }

    @PutMapping("/knowledge/{id}")
    Object updateKnowledge(@PathVariable Long id, @Valid @RequestBody KnowledgeRequest request) {
        return knowledgeDto(saveKnowledge(knowledge.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)), request));
    }

    @DeleteMapping("/knowledge/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteKnowledge(@PathVariable Long id) { knowledge.deleteById(id); }

    @GetMapping("/journals")
    Object journals() { return journals.findAllByOrderByHappenedAtDescCreatedAtDesc().stream().map(this::journalDto).toList(); }

    @PostMapping("/journals")
    Object createJournal(@Valid @RequestBody JournalRequest request) { return journalDto(saveJournal(new JournalEntry(), request)); }

    @PutMapping("/journals/{id}")
    Object updateJournal(@PathVariable Long id, @Valid @RequestBody JournalRequest request) {
        return journalDto(saveJournal(journals.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)), request));
    }

    @DeleteMapping("/journals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteJournal(@PathVariable Long id) { journals.deleteById(id); }

    @PostMapping(value = "/knowledge/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object importKnowledge(@RequestPart("file") MultipartFile file) { return importDocument(file); }

    private Post savePost(Post post, PostRequest request) {
        post.slug = request.slug(); post.titleZh = request.titleZh(); post.titleEn = request.titleEn(); post.category = request.category(); post.tags = request.tags();
        post.summaryZh = request.summaryZh(); post.summaryEn = request.summaryEn(); post.contentZh = request.contentZh(); post.contentEn = request.contentEn();
        post.coverObjectKey = request.coverObjectKey(); post.published = request.published(); post.updatedAt = Instant.now();
        return posts.save(post);
    }

    private Map<String, Object> postDto(Post post) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", post.id); dto.put("slug", post.slug); dto.put("titleZh", post.titleZh); dto.put("titleEn", post.titleEn); dto.put("category", post.category == null ? "" : post.category);
        dto.put("tags", post.tags == null ? "" : post.tags); dto.put("summaryZh", post.summaryZh == null ? "" : post.summaryZh); dto.put("summaryEn", post.summaryEn == null ? "" : post.summaryEn);
        dto.put("contentZh", post.contentZh == null ? "" : post.contentZh); dto.put("contentEn", post.contentEn == null ? "" : post.contentEn); dto.put("coverObjectKey", post.coverObjectKey == null ? "" : post.coverObjectKey); dto.put("published", post.published);
        return dto;
    }

    private Map<String, Object> adminJobDto(AiJob job) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", job.id); dto.put("provider", job.provider); dto.put("type", job.type); dto.put("prompt", job.prompt); dto.put("status", job.status);
        dto.put("model", job.model);
        dto.put("resultUrl", generator.resultUrl(job)); dto.put("downloadUrl", generator.downloadUrl(job));
        dto.put("inGallery", job.resultObjectKey != null && media.findByObjectKey(job.resultObjectKey).isPresent());
        dto.put("error", job.errorMessage == null ? "" : job.errorMessage); dto.put("createdAt", job.createdAt);
        return dto;
    }

    private KnowledgeEntry saveKnowledge(KnowledgeEntry item, KnowledgeRequest request) {
        item.title = request.title().trim(); item.tags = request.tags(); item.kind = "PERSONA".equalsIgnoreCase(request.kind()) ? "PERSONA" : "KNOWLEDGE";
        item.content = request.content().trim(); item.enabled = request.enabled(); item.updatedAt = Instant.now();
        return knowledge.save(item);
    }

    private Map<String, Object> knowledgeDto(KnowledgeEntry item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.id); dto.put("title", item.title); dto.put("tags", item.tags == null ? "" : item.tags); dto.put("kind", item.kind == null ? "KNOWLEDGE" : item.kind); dto.put("content", item.content);
        dto.put("enabled", item.enabled); dto.put("updatedAt", item.updatedAt);
        return dto;
    }

    private JournalEntry saveJournal(JournalEntry item, JournalRequest request) {
        item.titleZh = request.titleZh().trim(); item.titleEn = Objects.requireNonNullElse(request.titleEn(), "");
        item.contentZh = request.contentZh().trim(); item.contentEn = Objects.requireNonNullElse(request.contentEn(), ""); item.mood = Objects.requireNonNullElse(request.mood(), "");
        item.happenedAt = request.happenedAt() == null ? java.time.LocalDate.now() : request.happenedAt(); item.published = request.published(); item.updatedAt = Instant.now();
        return journals.save(item);
    }

    private Map<String, Object> journalDto(JournalEntry item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.id); dto.put("titleZh", item.titleZh); dto.put("titleEn", Objects.requireNonNullElse(item.titleEn, "")); dto.put("contentZh", item.contentZh); dto.put("contentEn", Objects.requireNonNullElse(item.contentEn, ""));
        dto.put("mood", Objects.requireNonNullElse(item.mood, "")); dto.put("happenedAt", item.happenedAt); dto.put("published", item.published); dto.put("createdAt", item.createdAt); dto.put("updatedAt", item.updatedAt);
        return dto;
    }

    private void ensurePrompt(MediaItem item) {
        if ((item.promptZh != null && !item.promptZh.isBlank()) || !"IMAGE".equalsIgnoreCase(item.mediaType)) return;
        String title = item.titleZh == null || item.titleZh.isBlank() ? "未命名影像" : item.titleZh;
        try {
            Map<String, String> prompts = generator.describeImage(oss.presignedGetUrl(item.objectKey), title);
            item.promptZh = prompts.get("promptZh"); item.promptEn = prompts.get("promptEn");
        } catch (Exception error) {
            item.promptZh = "以“" + title + "”为主题，突出主体、柔和光影、细腻质感与协调色彩，画面具有完整构图和温暖氛围。";
            item.promptEn = "Create an image inspired by \"" + title + "\", with a clear subject, soft lighting, fine texture, a balanced palette, polished composition, and a warm atmosphere.";
        }
    }
}

@Configuration
class SeedData {
    record SeedPost(String slug, String titleZh, String titleEn, String category, String tags,
                    String summaryZh, String summaryEn, String contentZh, String contentEn) {}

    @Bean
    CommandLineRunner seed(PostRepository posts, KnowledgeRepository knowledge) {
        return args -> {
            var seedPosts = new ObjectMapper().readValue(
                new ClassPathResource("blogs/interview-posts.json").getInputStream(),
                new TypeReference<List<SeedPost>>() {});
            for (SeedPost post : seedPosts) {
                saveSeed(posts, post.slug(), post.titleZh(), post.titleEn(), post.category(), post.tags(),
                    post.summaryZh(), post.summaryEn(), seedContent(post.contentZh()), post.contentEn());
            }
            saveKnowledge(knowledge, "关于 Wynn", "个人资料,站主", "KNOWLEDGE", "Wynn 的公开昵称是 Lonely__Runner，常用英文签名是 Wynn Yale Yox，所在城市是洛阳。他是一名全栈开发者与 AI 应用实践者，关注 Java、Spring、Spring AI、Vue、MySQL、RAG 与 Agent 工程，也喜欢影像创作和轻量小游戏。网站理念是“随性而行，无拘无定”。");
            saveKnowledge(knowledge, "团子的聊天方式", "人格,小猫,语气", "PERSONA", "团子是网站里的毛茸茸小猫助手。它温柔、机灵、尊重事实，回答技术问题时清晰直接，聊生活与创作时轻松友好。它可以适度使用“喵”和猫爪符号，但不会为了可爱牺牲信息质量，也不会假装知道知识库中没有的 Wynn 私人经历。");
        };
    }

    private void saveSeed(PostRepository posts, String slug, String titleZh, String titleEn, String category, String tags,
                          String summaryZh, String summaryEn, String contentZh, String contentEn) {
        Post post = posts.findBySlug(slug).orElseGet(() -> new Post(slug, titleZh, titleEn, category));
        post.titleZh = titleZh; post.titleEn = titleEn; post.category = category; post.tags = tags;
        post.summaryZh = summaryZh; post.summaryEn = summaryEn; post.contentZh = contentZh; post.contentEn = contentEn;
        post.published = true; post.updatedAt = Instant.now();
        posts.save(post);
    }

    private String seedContent(String value) {
        if (value == null || !value.startsWith("classpath:")) return value;
        try {
            return new ClassPathResource(value.substring("classpath:".length())).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read blog article: " + value, error);
        }
    }

    private void saveKnowledge(KnowledgeRepository knowledge, String title, String tags, String kind, String content) {
        var existing = knowledge.findByTitle(title);
        KnowledgeEntry item = existing.orElseGet(() -> new KnowledgeEntry(title, tags, content));
        item.tags = tags; item.kind = kind; item.content = content; if (existing.isEmpty()) item.enabled = true; item.updatedAt = Instant.now();
        knowledge.save(item);
    }
}
