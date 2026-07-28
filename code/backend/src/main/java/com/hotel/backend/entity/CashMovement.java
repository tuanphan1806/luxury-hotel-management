package com.hotel.backend.entity;

import com.hotel.backend.constant.CashMovementDirection;
import com.hotel.backend.constant.CashMovementSourceType;
import com.hotel.backend.constant.CashMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cash_movements")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_shift_id", nullable = false)
    private CashierShift cashierShift;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 32)
    private CashMovementType movementType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CashMovementDirection direction;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private CashMovementSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 191)
    private String sourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private PaymentRefund refund;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", nullable = false, length = 150)
    private String createdByName;

    @Column(name = "created_by_role", nullable = false, length = 32)
    private String createdByRole;

    @Column(length = 500)
    private String reason;

    @Column(name = "occurred_at_utc", nullable = false)
    private Instant occurredAtUtc;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;
}
