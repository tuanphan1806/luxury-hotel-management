package com.hotel.backend.config;

import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.UserTokenRepository;
import com.hotel.backend.service.JwtService;
import com.hotel.backend.service.UserServiceDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomizeRequestFilterTest {

    private CustomizeRequestFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CustomizeRequestFilter(
                mock(JwtService.class),
                mock(UserServiceDetail.class),
                mock(InvalidatedTokenRepository.class),
                mock(UserTokenRepository.class));
    }

    @Test
    void shouldSkipJwtFilterForPublicPostEndpoints() {
        assertThat(shouldNotFilter("POST", "/api/pricing/quote")).isTrue();
        assertThat(shouldNotFilter("POST", "/api/reservations/lookup")).isTrue();
    }

    @Test
    void shouldSkipJwtFilterOnlyForPublicAddOnCatalogReads() {
        assertThat(shouldNotFilter("GET", "/api/add-on-services")).isTrue();
        assertThat(shouldNotFilter("GET", "/api/add-on-services/")).isTrue();
        assertThat(shouldNotFilter("GET", "/api/add-on-services/42")).isTrue();

        assertThat(shouldNotFilter("GET", "/api/add-on-services/admin")).isFalse();
        assertThat(shouldNotFilter("POST", "/api/add-on-services/admin")).isFalse();
    }

    @Test
    void shouldKeepJwtFilterForReservationMutationsAndPrivateReads() {
        assertThat(shouldNotFilter("POST", "/api/reservations")).isFalse();
        assertThat(shouldNotFilter("GET", "/api/reservations/42")).isFalse();
    }

    private boolean shouldNotFilter(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return filter.shouldNotFilter(request);
    }
}
