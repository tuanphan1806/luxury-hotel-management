package com.hotel.backend.service;

import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.entity.OAuthLoginTicket;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.OAuthLoginTicketRepository;
import com.hotel.backend.util.SecurityTokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Mock
    private OAuthLoginTicketRepository repository;
    @Mock
    private AuthenticationService authenticationService;

    private OAuthLoginTicketService service;

    @BeforeEach
    void setUp() {
        service = new OAuthLoginTicketService(
                repository,
                authenticationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                120);
    }

    @Test
    void issueStoresOnlyHashAndShortExpiry() {
        User user = User.builder().username("oauth-user").build();
        when(repository.saveAndFlush(any(OAuthLoginTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String rawTicket = service.issue(user);

        ArgumentCaptor<OAuthLoginTicket> captor = ArgumentCaptor.forClass(OAuthLoginTicket.class);
        verify(repository).saveAndFlush(captor.capture());
        OAuthLoginTicket saved = captor.getValue();
        assertThat(rawTicket).hasSizeGreaterThanOrEqualTo(40);
        assertThat(saved.getTokenHash()).isEqualTo(SecurityTokenHasher.sha256(rawTicket));
        assertThat(saved.getTokenHash()).doesNotContain(rawTicket);
        assertThat(saved.getExpiresAtUtc()).isEqualTo(NOW.plusSeconds(120));
        assertThat(saved.getUser()).isSameAs(user);
    }

    @Test
    void exchangeConsumesTicketAndIssuesTokensOnce() {
        User user = User.builder().username("oauth-user").build();
        OAuthLoginTicket ticket = OAuthLoginTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256("raw-ticket"))
                .user(user)
                .expiresAtUtc(NOW.plusSeconds(30))
                .build();
        TokenResponse tokens = TokenResponse.builder()
                .accessToken("access")
                .refreshToken("refresh")
                .build();
        when(repository.findByTokenHashForUpdate(ticket.getTokenHash()))
                .thenReturn(Optional.of(ticket));
        when(authenticationService.issueTokens(user)).thenReturn(tokens);

        assertThat(service.exchange("raw-ticket")).isSameAs(tokens);
        assertThat(ticket.getConsumedAtUtc()).isEqualTo(NOW);
        verify(authenticationService).issueTokens(user);
    }

    @Test
    void exchangeRejectsConsumedOrExpiredTicket() {
        OAuthLoginTicket consumed = OAuthLoginTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256("consumed"))
                .user(User.builder().username("oauth-user").build())
                .expiresAtUtc(NOW.plusSeconds(30))
                .consumedAtUtc(NOW.minusSeconds(1))
                .build();
        when(repository.findByTokenHashForUpdate(consumed.getTokenHash()))
                .thenReturn(Optional.of(consumed));

        assertInvalid(() -> service.exchange("consumed"));

        OAuthLoginTicket expired = OAuthLoginTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256("expired"))
                .user(consumed.getUser())
                .expiresAtUtc(NOW)
                .build();
        when(repository.findByTokenHashForUpdate(expired.getTokenHash()))
                .thenReturn(Optional.of(expired));

        assertInvalid(() -> service.exchange("expired"));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OAUTH_EXCHANGE_TICKET_INVALID));
    }
}
