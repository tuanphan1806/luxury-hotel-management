package com.hotel.backend.dto.response;

import com.hotel.backend.constant.RefundChannel;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationInvoiceResponse {
    private String invoiceNumber;
    private Long reservationId;
    private String reservationCode;
    private LocalDateTime issuedAt;
    private Instant issuedAtUtc;
    private String hotelName;
    private String hotelAddress;
    private String hotelPhone;
    private String hotelEmail;
    private String hotelTaxCode;

    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String customerAddress;

    private LocalDateTime plannedCheckIn;
    private LocalDateTime plannedCheckOut;
    private LocalDateTime actualCheckIn;
    private LocalDateTime actualCheckOut;
    private Integer guestCount;
    private String note;

    private List<RoomTypeLine> roomTypes;
    private List<PaymentLine> payments;

    private BigDecimal plannedRoomCharge;
    private BigDecimal roomCharge;
    private BigDecimal actualRoomCharge;
    private BigDecimal earlyCheckoutAdjustment;
    private BigDecimal lateCheckoutFee;
    private BigDecimal checkoutAdditionalFee;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private Long grossPaidAmount;
    private Long refundedAmount;
    private Long completedRefundAmount;
    private Long netPaidAmount;
    private Long balanceAmount;
    private Long remainingAmount;
    private String settlementStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomTypeLine {
        private String roomTypeName;
        private Integer quantity;
        private BigDecimal pricePerRoomForStay;
        private BigDecimal plannedSubtotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentLine {
        private String transactionId;
        private String transactionReference;
        private String provider;
        private String purpose;
        private String status;
        private Long amount;
        private Long refundAmount;
        private String refundProvider;
        private RefundChannel refundChannel;
        private LocalDateTime paidAt;
        private Instant paidAtUtc;
        private LocalDateTime createdAt;
    }
}
