package com.hotel.backend.entity;

import com.hotel.backend.constant.OAuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(
    name = "oauth_accounts",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_oauth_accounts_provider_subject",
            columnNames = {"provider", "provider_subject"}
        ),
        @UniqueConstraint(
            name = "uk_oauth_accounts_user_provider",
            columnNames = {"user_id", "provider"}
        )
    },
    indexes = {
        @Index(name = "idx_oauth_accounts_user_id", columnList = "user_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount extends AbstractEntity<Long> implements Serializable {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
