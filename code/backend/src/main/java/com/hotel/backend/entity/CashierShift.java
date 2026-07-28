package com.hotel.backend.entity;

import com.hotel.backend.constant.CashierShiftStatus;
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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cashier_shifts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashierShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "shift_code", nullable = false, unique = true, length = 48)
    private String shiftCode;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CashierShiftStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opened_by", nullable = false)
    private User openedBy;

    @Column(name = "opened_by_name", nullable = false, length = 150)
    private String openedByName;

    @Column(name = "opened_by_role", nullable = false, length = 32)
    private String openedByRole;

    @Column(name = "opened_at_utc", nullable = false)
    private Instant openedAtUtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @Column(name = "closed_by_name", length = 150)
    private String closedByName;

    @Column(name = "closed_by_role", length = 32)
    private String closedByRole;

    @Column(name = "closed_at_utc")
    private Instant closedAtUtc;

    @Column(name = "opening_cash_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal openingCashAmount;

    @Column(name = "expected_cash_amount", precision = 19, scale = 0)
    private BigDecimal expectedCashAmount;

    @Column(name = "counted_cash_amount", precision = 19, scale = 0)
    private BigDecimal countedCashAmount;

    @Column(name = "variance_amount", precision = 19, scale = 0)
    private BigDecimal varianceAmount;

    @Column(length = 1000)
    private String note;

    @Column(name = "close_note", length = 1000)
    private String closeNote;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;
}
