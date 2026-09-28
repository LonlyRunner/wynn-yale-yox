package io.github.lonlyrunner.wynn;

import jakarta.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

@Entity @Table(name = "site_services")
class SiteServices {
    @Id Long id = 1L;
    boolean aiCreationEnabled = true;
    boolean catChatEnabled = true;
}
interface SiteServicesRepository extends JpaRepository<SiteServices, Long> {}

@Service
class SiteServiceSwitches {
    private final SiteServicesRepository repository;
    SiteServiceSwitches(SiteServicesRepository repository) { this.repository = repository; }
    SiteServices read() { return repository.findById(1L).orElseGet(SiteServices::new); }
    Map<String, Boolean> dto() { SiteServices value = read(); return Map.of("aiCreationEnabled", value.aiCreationEnabled, "catChatEnabled", value.catChatEnabled); }
    Object save(ServiceSwitchRequest request) {
        SiteServices value = read(); value.aiCreationEnabled = request.aiCreationEnabled(); value.catChatEnabled = request.catChatEnabled();
        repository.saveAndFlush(value); return dto();
    }
}
record ServiceSwitchRequest(@NotNull Boolean aiCreationEnabled, @NotNull Boolean catChatEnabled) {}

@Configuration
class ServiceSwitchGuard implements WebMvcConfigurer, HandlerInterceptor {
    private final SiteServiceSwitches switches;
    ServiceSwitchGuard(SiteServiceSwitches switches) { this.switches = switches; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/ai/jobs", "/api/chat/messages", "/api/chat/messages/multimodal"); }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) return true;
        SiteServices settings = switches.read();
        boolean ai = request.getRequestURI().equals("/api/ai/jobs");
        if (ai ? settings.aiCreationEnabled : settings.catChatEnabled) return true;
        // 423 preserves this message through nginx's generic 503 maintenance handler.
        response.setStatus(423); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"SERVICE_DISABLED\",\"message\":\"" + (ai ? "AI 创作服务已暂停，历史结果仍可查看和下载" : "团子聊天已暂停，历史记录仍可查看") + "\"}");
        return false;
    }
}

@Entity
@Table(name = "visitor_events", indexes = {@Index(name = "idx_visit_day", columnList = "visitedDay"), @Index(name = "idx_visit_visitor", columnList = "visitorId")})
class VisitorEvent {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String visitorId;
    @Column(nullable = false, length = 160) String path;
    @Column(nullable = false, length = 10) String device;
    @Column(nullable = false, length = 200) String source;
    @Column(nullable = false) LocalDate visitedDay;
    Instant createdAt = Instant.now();
}
interface VisitorEventRepository extends JpaRepository<VisitorEvent, String> {
    long countByVisitedDay(LocalDate day);
    @Query("select count(distinct v.visitorId) from VisitorEvent v where v.visitedDay >= :start") long visitors(LocalDate start);
    @Query("select v.visitedDay, count(v), count(distinct v.visitorId) from VisitorEvent v where v.visitedDay >= :start group by v.visitedDay order by v.visitedDay") List<Object[]> daily(LocalDate start);
    @Query("select v.path, count(v) from VisitorEvent v where v.visitedDay >= :start group by v.path order by count(v) desc") List<Object[]> pages(LocalDate start);
    @Query("select v.device, count(v) from VisitorEvent v where v.visitedDay >= :start group by v.device order by count(v) desc") List<Object[]> devices(LocalDate start);
    @Query("select v.source, count(v) from VisitorEvent v where v.visitedDay >= :start group by v.source order by count(v) desc") List<Object[]> sources(LocalDate start);
}
record VisitRequest(@NotNull @Pattern(regexp = "[a-fA-F0-9-]{36}") String eventId,
                    @NotNull @Size(max = 160) String path, @Size(max = 2000) String referrer) {}

@RestController
class SiteOperationsController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final SiteServiceSwitches switches;
    private final VisitorEventRepository visits;
    SiteOperationsController(SiteServiceSwitches switches, VisitorEventRepository visits) { this.switches = switches; this.visits = visits; }

    @GetMapping({"/api/public/services", "/api/admin/services"})
    Object services(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); return switches.dto(); }
    @PutMapping("/api/admin/services")
    Object services(@Valid @RequestBody ServiceSwitchRequest request) { return switches.save(request); }

    @PostMapping("/api/public/visits") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void visit(@Valid @RequestBody VisitRequest body, HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        if (AuthController.isOwner(auth) || !body.path().matches("/(|blog(/[a-zA-Z0-9_-]+)?|journal|gallery|share|guestbook|studio|about|privacy|terms|games(/[a-zA-Z0-9_-]+)?)")) return;
        String eventId;
        try { eventId = UUID.fromString(body.eventId()).toString(); } catch (IllegalArgumentException invalid) { return; }
        if (visits.existsById(eventId)) return;
        String visitor = null;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if ("wynn_visitor".equals(cookie.getName())) {
                try { visitor = UUID.fromString(cookie.getValue()).toString(); } catch (IllegalArgumentException ignored) {}
            }
        }
        if (visitor == null) {
            visitor = UUID.randomUUID().toString();
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("wynn_visitor", visitor).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/").maxAge(Duration.ofDays(365)).build().toString());
        }
        VisitorEvent entry = new VisitorEvent(); entry.id = eventId; entry.visitorId = visitor; entry.path = body.path();
        String agent = Objects.requireNonNullElse(request.getHeader("User-Agent"), "").toLowerCase(Locale.ROOT);
        entry.device = agent.contains("ipad") || agent.contains("tablet") || agent.contains("android") && !agent.contains("mobile") ? "tablet"
            : agent.contains("mobile") || agent.contains("iphone") ? "mobile" : "desktop";
        entry.source = source(body.referrer()); entry.visitedDay = LocalDate.now(ZONE);
        try { visits.saveAndFlush(entry); } catch (DataIntegrityViolationException duplicate) { if (!visits.existsById(eventId)) throw duplicate; }
    }

    @GetMapping("/api/admin/visitors")
    Object visitors(@RequestParam(defaultValue = "30") int days, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        int period = List.of(7, 30, 90).contains(days) ? days : 30;
        LocalDate today = LocalDate.now(ZONE), start = today.minusDays(period - 1);
        Map<LocalDate, Object[]> counted = new HashMap<>();
        for (Object[] row : visits.daily(start)) counted.put((LocalDate) row[0], row);
        List<Map<String, Object>> trend = new ArrayList<>(); long pv = 0;
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            Object[] row = counted.get(day); long count = row == null ? 0 : ((Number) row[1]).longValue(); pv += count;
            trend.add(Map.of("day", day.toString(), "pv", count, "uv", row == null ? 0 : ((Number) row[2]).longValue()));
        }
        return Map.of("days", period, "todayPv", visits.countByVisitedDay(today), "todayUv", visits.visitors(today),
            "totalPv", visits.count(), "totalUv", visits.visitors(LocalDate.of(1970, 1, 1)), "periodPv", pv, "periodUv", visits.visitors(start),
            "trend", trend, "breakdown", Map.of("pages", counts(visits.pages(start)), "devices", counts(visits.devices(start)), "sources", counts(visits.sources(start))));
    }
    private List<Map<String, Object>> counts(List<Object[]> rows) { return rows.stream().limit(20).map(row -> Map.<String, Object>of("name", row[0], "value", row[1])).toList(); }
    private String source(String referrer) {
        try {
            URI uri = URI.create(Objects.requireNonNullElse(referrer, ""));
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (host != null && Set.of("http", "https").contains(uri.getScheme()) && !host.equals("wynnyaleyox.me") && !host.endsWith(".wynnyaleyox.me") && !host.equals("localhost")) return host.substring(0, Math.min(200, host.length())).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {}
        return "直接 / 站内访问";
    }
}
