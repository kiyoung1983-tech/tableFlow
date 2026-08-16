package com.example.fullstack.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(
        prefix = "app.public-rate-limit",
        name = "enabled",
        havingValue = "true")
public class PublicApiRateLimitFilter extends OncePerRequestFilter {
    private static final String CREATE_PATH = "/api/public/reservations";
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int createLimit;
    private final int managementLimit;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupWindow = new AtomicLong(Long.MIN_VALUE);

    public PublicApiRateLimitFilter(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.public-rate-limit.create-limit:10}") int createLimit,
            @Value("${app.public-rate-limit.management-limit:30}") int managementLimit,
            @Value("${app.public-rate-limit.window-seconds:60}") long windowSeconds) {
        if (createLimit < 1 || managementLimit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("공개 API rate limit 값은 1 이상이어야 합니다.");
        }
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.createLimit = createLimit;
        this.managementLimit = managementLimit;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return routeGroup(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var routeGroup = routeGroup(request);
        if (routeGroup == null) {
            filterChain.doFilter(request, response);
            return;
        }
        var now = clock.instant().getEpochSecond();
        var window = now / windowSeconds;
        cleanupOldWindows(window);
        var key = routeGroup + ":" + request.getRemoteAddr();
        if (!counters.containsKey(key) && counters.size() >= MAX_TRACKED_CLIENTS) {
            writeRateLimitProblem(request, response, now);
            return;
        }
        var counter = counters.compute(key, (ignored, current) ->
                current == null || current.window() != window
                        ? new WindowCounter(window, 1)
                        : new WindowCounter(window, current.count() + 1));
        var limit = routeGroup.equals("create") ? createLimit : managementLimit;
        if (counter.count() > limit) {
            writeRateLimitProblem(request, response, now);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String routeGroup(HttpServletRequest request) {
        var path = request.getRequestURI();
        var method = request.getMethod();
        if (CREATE_PATH.equals(path) && "POST".equals(method)) return "create";
        if (path.startsWith(CREATE_PATH + "/")
                && ("GET".equals(method) || "PATCH".equals(method) || "POST".equals(method))) {
            return "management";
        }
        return null;
    }

    private void writeRateLimitProblem(
            HttpServletRequest request, HttpServletResponse response, long now) throws IOException {
        var retryAfter = windowSeconds - now % windowSeconds;
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        problem.setTitle("Too Many Requests");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "RATE_LIMIT_EXCEEDED");
        var traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        if (traceId != null) problem.setProperty("traceId", traceId.toString());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private void cleanupOldWindows(long currentWindow) {
        var previousWindow = lastCleanupWindow.get();
        if (previousWindow >= currentWindow
                || !lastCleanupWindow.compareAndSet(previousWindow, currentWindow)) {
            return;
        }
        counters.entrySet().removeIf(entry -> entry.getValue().window() < currentWindow - 1);
    }

    int trackedClientCount() {
        return counters.size();
    }

    private record WindowCounter(long window, int count) {}
}
