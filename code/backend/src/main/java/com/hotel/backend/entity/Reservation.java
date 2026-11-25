package com.hotel.backend.entity;

import com.hotel.backend.constant.PaymentPlan;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.StayPackage;
import jakarta.persistence.*;
import lombok.*;

import java.util.Set;
import java.util.HashSet;
import java.io.Serializable;
import java.math.BigDecimal;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservations")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class Reservation extends AbstractEntity<Long> implements Serializable {

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "reservation_code", unique = true, nullable = false, length = 50)
    private String reservationCode;

    @Column(name = "guest_token", unique = true, length = 64)
    private String guestToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(name = "check_in", nullable = false)
    private LocalDateTime checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDateTime checkOut;

    @Column(name = "actual_check_in")
    private LocalDateTime actualCheckIn;

    @Column(name = "actual_check_out")
    private LocalDateTime actualCheckOut;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_version", nullable = false, length = 32)
    private PricingAlgorithmVersion pricingVersion =
            PricingAlgorithmVersion.LEGACY_V1;

    /**
     * Display-only aggregate. Financial calculations must use line snapshots.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "display_package_summary", length = 24)
    private StayPackage displayPackageSummary;

    @Column(name = "inventory_protected_until")
    private LocalDateTime inventoryProtectedUntil;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_plan", nullable = false, length = 24)
    private PaymentPlan paymentPlan = PaymentPlan.DEPOSIT_50;

    /** Số tiền phải thu ở lần thanh toán đầu, được snapshot khi tạo đơn. */
    @Builder.Default
    @Column(name = "required_initial_payment", precision = 12, scale = 2, nullable = false)
    private BigDecimal requiredInitialPayment = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "late_checkout_fee", precision = 12, scale = 2, nullable = false)
    private BigDecimal lateCheckoutFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "early_checkout_adjustment", precision = 12, scale = 2, nullable = false)
    private BigDecimal earlyCheckoutAdjustment = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "checkout_additional_fee", precision = 12, scale = 2, nullable = false)
    private BigDecimal checkoutAdditionalFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "discount_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "VARCHAR(32)")
    private ReservationStatus status= ReservationStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_cancellation", length = 32, columnDefinition = "VARCHAR(32)")
    private ReservationStatus statusBeforeCancellation;

    @Builder.Default
    @Column(name = "cancellation_fee", precision = 12, scale = 2, nullable = false)
    private BigDecimal cancellationFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "refundable_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal refundableAmount = BigDecimal.ZERO;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Column(name = "cancellation_reason_code", length = 64)
    private String cancellationReasonCode;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "guest_count")
    private Integer guestCount;

    public static int departedGuests(java.util.Collection<ReservationRoomType> lines) {
        return lines.stream().flatMap(line -> line.getRooms().stream()).map(ReservationRoom::getDeparture)
                .filter(java.util.Objects::nonNull).mapToInt(RoomDeparture::getGuestCount).sum();
    }

    public int inHouseGuestCount(java.util.Collection<ReservationRoomType> lines) {
        if (status == ReservationStatus.CHECKED_OUT || actualCheckOut != null) return 0;
        int departed = departedGuests(lines);
        int total = guestCount == null ? 0 : guestCount;
        // Earlier guest corrections wrote the active headcount on the booking. The V2 lines
        // retain the full corrected distribution, including guests on issued room folios.
        if (departed > 0 && pricingVersion == PricingAlgorithmVersion.MOTEL_PACKAGE_V2
                && lines.stream().allMatch(line -> line.getLineGuestCount() != null)) {
            total = lines.stream().mapToInt(ReservationRoomType::getLineGuestCount).sum();
        }
        return Math.max(0, total - departed);
    }

    @Builder.Default
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PaymentTransaction> transactions = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ReservationRoomType> roomTypes = new HashSet<>();
}
