package com.hotel.backend.entity;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    // Mã giao dịch nội bộ (gửi sang cổng thanh toán)
    @Column(name = "txn_ref", unique = true, nullable = false)
    private String txnRef;

    // Mã giao dịch từ cổng thanh toán trả về
    @Column(name = "provider_txn_id")
    private String providerTxnId;

    /** Mã tham chiếu ngân hàng, dùng để chống ghi nhận trùng giữa webhook và API đối soát. */
    @Column(name = "provider_reference")
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32,
            columnDefinition = "VARCHAR(32) DEFAULT 'FINAL_PAYMENT'")
    private com.hotel.backend.constant.PaymentPurpose purpose =
            com.hotel.backend.constant.PaymentPurpose.FINAL_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    // Số tiền (VND)
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** Số tiền QR yêu cầu ban đầu. */
    @Column(name = "expected_amount")
    private Long expectedAmount;

    /** Số tiền SePay xác nhận đã vào tài khoản merchant. */
    @Column(name = "received_amount")
    private Long receivedAmount;

    /**
     * Phần tiền khách sạn thực sự phân bổ cho nghĩa vụ của reservation.
     * NULL biểu thị row legacy chưa được đối soát chắc chắn.
     */
    @Column(name = "accepted_amount")
    private Long acceptedAmount;

    /**
     * Phần tiền đã nhận nhưng phải hoàn. NULL biểu thị allocation legacy chưa
     * được quyết định; không được diễn giải như 0.
     */
    @Column(name = "refund_required_amount")
    private Long refundRequiredAmount;

    @Column(name = "currency", nullable = false)
    @Builder.Default
    private String currency = "VND";

    // Nội dung thanh toán
    @Column(name = "order_info")
    private String orderInfo;

    // IP khách hàng
    @Column(name = "ip_address")
    private String ipAddress;

    // Mã/kênh ngân hàng do nhà cung cấp trả về
    @Column(name = "bank_code")
    private String bankCode;

    /** Kênh khách chọn khi tạo URL: VNPAYQR, VNBANK hoặc INTCARD. */
    @Column(name = "requested_bank_code", length = 20)
    private String requestedBankCode;

    /** vnp_CreateDate đã ký trong URL PAY; bắt buộc dùng lại khi refund/querydr. */
    @Column(name = "provider_create_date", length = 14)
    private String providerCreateDate;

    @Column(name = "provider_pay_date", length = 32)
    private String providerPayDate;

    @Column(name = "card_type", length = 20)
    private String cardType;

    // Mã phản hồi từ cổng thanh toán
    @Column(name = "response_code")
    private String responseCode;

    // Thông báo lỗi (nếu có)
    @Column(name = "message")
    private String message;

    // Token lưu thẻ (dùng cho thanh toán lần sau)
    @Column(name = "card_token")
    private String cardToken;

    // Mã giao dịch hoàn tiền
    @Column(name = "refund_txn_id")
    private String refundTxnId;

    @Column(name = "refund_amount")
    private Long refundAmount;

    /** Phương thức dùng để hoàn tiền, tách biệt với phương thức thanh toán gốc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_provider", length = 20)
    private PaymentProvider refundProvider;

    @Column(name = "refund_completed_at")
    private LocalDateTime refundCompletedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Thời gian thanh toán thành công
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Canonical UTC timestamp for financial settlement; paidAt is retained for compatibility. */
    @Column(name = "paid_at_utc")
    private Instant paidAtUtc;

    /** Canonical UTC expiry boundary; expiresAt is retained for stay-time compatibility clients. */
    @Column(name = "expires_at_utc")
    private Instant expiresAtUtc;
}
