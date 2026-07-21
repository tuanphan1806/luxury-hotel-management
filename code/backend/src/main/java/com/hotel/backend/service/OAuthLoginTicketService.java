package com.hotel.backend.service;

import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.entity.OAuthLoginTicket;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.OAuthLoginTicketRepository;
import com.hotel.backend.util.SecurityTokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class OAuthLoginTicketService {

    private static final int TICKET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthLoginTicketRepository repository;
    private final AuthenticationService authenticationService;
    private final Clock clock;
    private final Duration ticketTtl;

    @Autowired
    public OAuthLoginTicketService(
            OAuthLoginTicketRepository repository,
            AuthenticationService authenticationService,
            @Value("${app.oauth.exchange-ticket-ttl-seconds:120}") long ticketTtlSeconds) {
        this(repository, authenticationService, Clock.systemUTC(), ticketTtlSeconds);
    }

    OAuthLoginTicketService(
            OAuthLoginTicketRepository repository,
            AuthenticationService authenticationService,
            Clock clock,
            long ticketTtlSeconds) {
        this.repository = repository;
        this.authenticationService = authenticationService;
        this.clock = clock;
        this.ticketTtl = Duration.ofSeconds(Math.max(30, Math.min(ticketTtlSeconds, 600)));
    }

    @Transactional
    public String issue(User user) {
        Instant now = clock.instant();
        repository.deleteExpired(now);

        byte[] randomBytes = new byte[TICKET_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        repository.saveAndFlush(OAuthLoginTicket.builder()
                .tokenHash(SecurityTokenHasher.sha256(ticket))
                .user(user)
                .expiresAtUtc(now.plus(ticketTtl))
                .build());
        return ticket;
    }

    @Transactional
    public TokenResponse exchange(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            throw invalidTicket();
        }

        Instant now = clock.instant();
        OAuthLoginTicket ticket = repository
                .findByTokenHashForUpdate(SecurityTokenHasher.sha256(rawTicket.trim()))
                .orElseThrow(this::invalidTicket);

        if (ticket.getConsumedAtUtc() != null || !ticket.getExpiresAtUtc().isAfter(now)) {
            throw invalidTicket();
        }

        // The pessimistic row lock makes this the only completion boundary.
        // If token issuance fails, the transaction rolls the consumed marker back.
        ticket.setConsumedAtUtc(now);
        return authenticationService.issueTokens(ticket.getUser());
    }

    private AppException invalidTicket() {
        return new AppException(ErrorCode.OAUTH_EXCHANGE_TICKET_INVALID);
    }
}
