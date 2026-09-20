package io.github.lonlyrunner.wynn;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.http.HttpHeaders;
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
record AiJobRequest(@NotBlank String type, @NotBlank @Size(max = 4000) String prompt, String aspectRatio, String resolution, Integer duration) {}
record CommentRequest(@NotBlank @Size(max = 80) String author, @Size(max = 160) String email, @NotBlank @Size(max = 2000) String content) {}
record PostRequest(@NotBlank String slug, @NotBlank String titleZh, @NotBlank String titleEn, String category, String tags, String summaryZh, String summaryEn, String contentZh, String contentEn, String coverObjectKey, boolean published) {}
record MediaRequest(@NotBlank String objectKey, String titleZh, String titleEn, String mediaType, String promptZh, String promptEn, int sortOrder) {}

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
    private final OssService oss;

    PublicController(PostRepository posts, CommentRepository comments, MediaRepository media, OssService oss) {
        this.posts = posts;
        this.comments = comments;
        this.media = media;
        this.oss = oss;
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

    private String signed(String key) { return key == null || key.isBlank() || !oss.configured() ? "" : oss.presignedGetUrl(key); }
    private String value(String value) { return value == null ? "" : value; }
    private String escapeXml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
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

    @PostMapping("/jobs")
    @Transactional
    Object create(@Valid @RequestBody AiJobRequest requestBody, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        String type = requestBody.type().toUpperCase(Locale.ROOT);
        if (!List.of("IMAGE", "VIDEO").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的创作类型");
        boolean owner = AuthController.isOwner(auth);
        String guestId = owner ? null : guestId(request, response);
        if (!owner) reserve(guestId, type);
        int duration = requestBody.duration() == null ? 5 : Math.max(1, Math.min(15, requestBody.duration()));
        String requestedResolution = requestBody.resolution();
        String resolution = requestedResolution != null && List.of("480p", "720p", "1080p").contains(requestedResolution) ? requestedResolution : "720p";
        String ratio = requestBody.aspectRatio() == null || requestBody.aspectRatio().isBlank() ? "16:9" : requestBody.aspectRatio();
        AiJob job = jobs.save(new AiJob("relay", type, requestBody.prompt().trim(), guestId, ratio, resolution, duration));
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
    private final OssService oss;
    private final AiGenerationService generator;
    private final Tika tika = new Tika();

    AdminController(AiJobRepository jobs, PostRepository posts, CommentRepository comments, MediaRepository media, OssService oss, AiGenerationService generator) {
        this.jobs = jobs; this.posts = posts; this.comments = comments; this.media = media; this.oss = oss; this.generator = generator;
    }

    @GetMapping("/ai/jobs")
    Object jobs() { return jobs.findAllByOrderByCreatedAtDesc().stream().map(job -> adminJobDto(generator.refresh(job))).toList(); }

    @DeleteMapping("/ai/jobs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteJob(@PathVariable Long id) {
        AiJob job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (job.resultObjectKey != null && oss.configured()) oss.delete(job.resultObjectKey);
        jobs.delete(job);
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
        dto.put("resultUrl", generator.resultUrl(job)); dto.put("downloadUrl", generator.downloadUrl(job));
        dto.put("error", job.errorMessage == null ? "" : job.errorMessage); dto.put("createdAt", job.createdAt);
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
    @Bean
    CommandLineRunner seed(PostRepository posts) {
        return args -> {
            saveSeed(posts, "reliable-agent", "从一次对话到一个可靠的 Agent", "From a Conversation to a Reliable Agent", "AI ENGINEERING", "Agent,Spring AI",
                "可靠不是让模型更聪明，而是让系统知道什么时候继续、什么时候停下来。", "Reliability comes from clear state boundaries, recovery, and evidence.",
                "# 从状态开始设计\n\n一个流畅的演示距离一个可长期运行的 Agent 仍有很远。真正困难的部分通常不是提示词，而是围绕模型建立清晰的状态边界。\n\n> 让每一步都有证据，让每一次失败都能回到可恢复的位置。",
                "# Start with state\n\nA polished demo is still far from an agent that can run reliably. The hard part is the boundary around every state.");
            saveSeed(posts, "model-routing", "多模型路由的简单实现", "A Simple Multi-model Router", "SPRING AI", "Spring AI,Routing",
                "用统一接口连接不同模型服务。", "Connect multiple model providers behind one stable interface.",
                "# 多模型路由\n\n把厂商差异收敛在服务层，页面只关心创作类型、参数和任务状态。主服务不可用时，图片任务可以自动切换到备用模型。",
                "# Multi-model routing\n\nKeep provider differences in the service layer. The UI only needs generation type, options, and job state.");
            saveSeed(posts, "context", "并发任务中的上下文传递", "Context Propagation in Concurrent Tasks", "JAVA", "Java,Concurrency",
                "在线程切换中保留必要上下文。", "Keep the right context across asynchronous boundaries.",
                "# 上下文传递\n\n异步任务只携带真正需要的数据，并在任务结束时及时清理，能减少线程复用带来的数据串扰。",
                "# Context propagation\n\nPass only the data an asynchronous task needs and clear it when the task completes.");
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
}
