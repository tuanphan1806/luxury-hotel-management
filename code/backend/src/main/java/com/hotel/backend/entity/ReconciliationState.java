package com.hotel.backend.entity;

import com.hotel.backend.constant.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "reconciliation_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reconciliation_provider_account",
                columnNames = {"provider", "merchant_account_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentProvider provider;

    @Column(name = "merchant_account_id", nullable = false, length = 255)
    private String merchantAccountId;

    @Column(name = "cursor_value", length = 512)
    private String cursorValue;

    @Column(name = "last_successful_occurred_at_utc")
    private Instant lastSuccessfulOccurredAtUtc;

    @Column(name = "last_run_at_utc")
    private Instant lastRunAtUtc;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @Version
    @Column(nullable = false)
    private Long version;
}
