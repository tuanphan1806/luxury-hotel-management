package com.hotel.backend.config;

import com.hotel.backend.scheduled.MaintenancePollGate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceWakeFilterTest {
    private final MaintenancePollGate gate = mock(MaintenancePollGate.class);
    private final MaintenanceWakeFilter filter = new MaintenanceWakeFilter(gate);

    @Test void healthProbesAndPreflightNeverWakeDatabasePolling() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness", "/favicon.ico"}) {
            filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), mock(FilterChain.class));
        }
        filter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/payments/sepay/webhook"),
                new MockHttpServletResponse(), mock(FilterChain.class));
        verifyNoInteractions(gate);
    }

    @Test void completedWebhookWakesAfterTheControllerEvenOnFailure() {
        var request = new MockHttpServletRequest("POST", "/api/payments/sepay/webhook");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            verifyNoInteractions(gate);
            throw new ServletException("after commit failure");
        })).isInstanceOf(ServletException.class);
        verify(gate).wake();
    }
}
