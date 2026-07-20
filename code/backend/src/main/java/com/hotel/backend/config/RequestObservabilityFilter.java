package com.hotel.backend.config;

import com.hotel.backend.service.BusinessMetricService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
@RequiredArgsConstructor
@Slf4j(topic = "HTTP-OBSERVABILITY")
public class RequestObservabilityFilter extends OncePerRequestFilter {
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String SEPAY_WEBHOOK_PATH = "/api/payments/sepay/webhook";

    private final BusinessMetricService metrics;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = correlationId(request.getHeader(CORRELATION_HEADER));
        response.setHeader(CORRELATION_HEADER, correlationId);
        long startedAt = System.nanoTime();
        boolean failedBeforeResponse = false;
        MDC.put("correlationId", correlationId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failedBeforeResponse = true;
            throw exception;
        } finally {
            int status = failedBeforeResponse && response.getStatus() < 400
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String role = authentication != null && authentication.isAuthenticated()
                    ? authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .sorted()
                    .collect(Collectors.joining(","))
                    : "ANONYMOUS";
            log.info("request_completed method={} path={} status={} durationMs={} actorRole={}",
                    request.getMethod(), request.getRequestURI(), status, durationMs, role);
            if (SEPAY_WEBHOOK_PATH.equals(request.getRequestURI())) {
                String statusClass = status >= 500 ? "5xx"
                        : status >= 400 ? "4xx" : status >= 300 ? "3xx" : "2xx";
                metrics.increment("hotel.sepay.webhook.requests", "status", statusClass);
                metrics.recordDuration(
                        "hotel.sepay.webhook.duration", startedAt, "status", statusClass);
            }
            MDC.remove("correlationId");
        }
    }

    private String correlationId(String supplied) {
        if (supplied != null && supplied.matches("[A-Za-z0-9._-]{1,128}")) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
