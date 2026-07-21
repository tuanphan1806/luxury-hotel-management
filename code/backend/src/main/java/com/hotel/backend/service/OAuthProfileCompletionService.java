package com.hotel.backend.service;

import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.entity.OAuthProfileCompletionTicket;
import com.hotel.backend.entity.User;
import com.hotel.backend.event.UserRegisteredEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.exception.OAuthLoginException;
import com.hotel.backend.repository.OAuthProfileCompletionTicketRepository;
import com.hotel.backend.util.SecurityTokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class OAuthProfileCompletionService {

    private static final int TICKET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthProfileCompletionTicketRepository repository;
    private final OAuthAccountService oauthAccountService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration ticketTtl;

    @Autowired
    public OAuthProfileCompletionService(
            OAuthProfileCompletionTicketRepository repository,
            OAuthAccountService oauthAccountService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.oauth.profile-completion-ticket-ttl-seconds:600}") long ticketTtlSeconds) {
        this(repository, oauthAccountService, eventPublisher, Clock.systemUTC(), ticketTtlSeconds);
    }

    OAuthProfileCompletionService(
            OAuthProfileCompletionTicketRepository repository,
            OAuthAccountService oauthAccountService,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            long ticketTtlSeconds) {
        this.repository = repository;
        this.oauthAccountService = oauthAccountService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.ticketTtl = Duration.ofSeconds(Math.max(120, Math.min(ticketTtlSeconds, 1_800)));
    }

    @Transactional
    public String issue(OAuthLoginProfile profile) {
        if (profile == null
                || profile.provider() != OAuthProvider.FACEBOOK
                || profile.providerSubject() == null
                || profile.providerSubject().isBlank()) {
            throw new OAuthLoginException(OAuthLoginError.PROVIDER_ERROR);
        }

        Instant now = clock.instant();
        repository.deleteExpired(now);
        byte[] randomBytes = new byte[TICKET_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        repository.saveAndFlush(OAuthProfileCompletionTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256(ticket))
                .provider(profile.provider())
                .providerSubject(profile.providerSubject().trim())
                .fullName(truncate(trimToNull(profile.fullName()), 255))
                .imageUrl(truncate(trimToNull(profile.imageUrl()), 500))
                .expiresAtUtc(now.plus(ticketTtl))
                .build());
        return ticket;
    }

    @Transactional
    public void complete(String rawTicket, String email) {
        if (rawTicket == null || rawTicket.isBlank()) {
            throw invalidTicket();
        }

        Instant now = clock.instant();
        OAuthProfileCompletionTicket ticket = repository
                .findByTokenHashForUpdate(SecurityTokenHasher.sha256(rawTicket.trim()))
                .orElseThrow(this::invalidTicket);
        if (ticket.getConsumedAtUtc() != null || !ticket.getExpiresAtUtc().isAfter(now)) {
            throw invalidTicket();
        }

        OAuthLoginProfile profile = new OAuthLoginProfile(
                ticket.getProvider(),
                ticket.getProviderSubject(),
                null,
                false,
                null,
                ticket.getFullName(),
                ticket.getImageUrl());
        User user = oauthAccountService.createPendingFacebookAccount(profile, email);
        ticket.setConsumedAtUtc(now);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
    }

    private AppException invalidTicket() {
        return new AppException(ErrorCode.OAUTH_PROFILE_TICKET_INVALID);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
