package com.hotel.backend.service;

import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.entity.OAuthProfileCompletionTicket;
import com.hotel.backend.entity.User;
import com.hotel.backend.event.UserRegisteredEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.OAuthProfileCompletionTicketRepository;
import com.hotel.backend.util.SecurityTokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthProfileCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Mock
    private OAuthProfileCompletionTicketRepository repository;
    @Mock
    private OAuthAccountService oauthAccountService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OAuthProfileCompletionService service;

    @BeforeEach
    void setUp() {
        service = new OAuthProfileCompletionService(
                repository,
                oauthAccountService,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                600);
    }

    @Test
    void issuesHashedShortLivedTicketWithoutPersistingRawToken() {
        when(repository.saveAndFlush(any(OAuthProfileCompletionTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String rawTicket = service.issue(facebookProfile());

        ArgumentCaptor<OAuthProfileCompletionTicket> captor =
                ArgumentCaptor.forClass(OAuthProfileCompletionTicket.class);
        verify(repository).saveAndFlush(captor.capture());
        OAuthProfileCompletionTicket stored = captor.getValue();
        assertThat(rawTicket).isNotBlank();
        assertThat(stored.getTokenHash()).isEqualTo(SecurityTokenHasher.sha256(rawTicket));
        assertThat(stored.getTokenHash()).doesNotContain(rawTicket);
        assertThat(stored.getProvider()).isEqualTo(OAuthProvider.FACEBOOK);
        assertThat(stored.getProviderSubject()).isEqualTo("facebook-subject");
        assertThat(stored.getExpiresAtUtc()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void completesTicketOnceAndPublishesVerificationEmailEvent() {
        String rawTicket = "profile-ticket";
        OAuthProfileCompletionTicket ticket = validTicket(rawTicket);
        User user = User.builder().email("guest@example.com").build();
        user.setId(42L);
        when(repository.findByTokenHashForUpdate(SecurityTokenHasher.sha256(rawTicket)))
                .thenReturn(Optional.of(ticket));
        when(oauthAccountService.createPendingFacebookAccount(
                any(OAuthLoginProfile.class), any(String.class)))
                .thenReturn(user);

        service.complete(rawTicket, "guest@example.com");

        assertThat(ticket.getConsumedAtUtc()).isEqualTo(NOW);
        ArgumentCaptor<OAuthLoginProfile> profileCaptor =
                ArgumentCaptor.forClass(OAuthLoginProfile.class);
        verify(oauthAccountService).createPendingFacebookAccount(
                profileCaptor.capture(), org.mockito.ArgumentMatchers.eq("guest@example.com"));
        assertThat(profileCaptor.getValue().provider()).isEqualTo(OAuthProvider.FACEBOOK);
        assertThat(profileCaptor.getValue().providerSubject()).isEqualTo("facebook-subject");
        verify(eventPublisher).publishEvent(new UserRegisteredEvent(42L));
    }

    @Test
    void rejectsExpiredOrConsumedTicketWithoutCreatingAccount() {
        String rawTicket = "expired-ticket";
        OAuthProfileCompletionTicket ticket = validTicket(rawTicket);
        ticket.setExpiresAtUtc(NOW);
        when(repository.findByTokenHashForUpdate(SecurityTokenHasher.sha256(rawTicket)))
                .thenReturn(Optional.of(ticket));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.complete(rawTicket, "guest@example.com"));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OAUTH_PROFILE_TICKET_INVALID);
        verify(oauthAccountService, never())
                .createPendingFacebookAccount(any(OAuthLoginProfile.class), any(String.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private OAuthLoginProfile facebookProfile() {
        return new OAuthLoginProfile(
                OAuthProvider.FACEBOOK,
                "facebook-subject",
                null,
                false,
                null,
                "Facebook Guest",
                "https://images.example/facebook.png");
    }

    private OAuthProfileCompletionTicket validTicket(String rawTicket) {
        return OAuthProfileCompletionTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256(rawTicket))
                .provider(OAuthProvider.FACEBOOK)
                .providerSubject("facebook-subject")
                .fullName("Facebook Guest")
                .imageUrl("https://images.example/facebook.png")
                .expiresAtUtc(NOW.plusSeconds(600))
                .build();
    }
}
