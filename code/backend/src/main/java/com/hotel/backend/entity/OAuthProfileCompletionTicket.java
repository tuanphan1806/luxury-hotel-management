package com.hotel.backend.entity;

import com.hotel.backend.constant.OAuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(
        name = "oauth_profile_completion_tickets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oauth_profile_completion_token_hash",
                columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_oauth_profile_completion_expiry", columnList = "expires_at_utc"),
                @Index(
                        name = "idx_oauth_profile_completion_identity",
                        columnList = "provider,provider_subject")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthProfileCompletionTicket implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "consumed_at_utc")
    private Instant consumedAtUtc;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;
}
