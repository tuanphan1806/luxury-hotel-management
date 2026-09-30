package com.hotel.backend.config;

import com.hotel.backend.scheduled.MaintenancePollGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 2)
@RequiredArgsConstructor
public class MaintenanceWakeFilter extends OncePerRequestFilter {
    private final MaintenancePollGate pollGate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return "OPTIONS".equals(request.getMethod())
                || !(path.startsWith("/api/") || path.startsWith("/auth/")
                || path.startsWith("/login/oauth2/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // Wake after business transactions commit, including partial 5xx outcomes.
            // Hosting probes never enter this filter.
            pollGate.wake();
        }
    }
}
