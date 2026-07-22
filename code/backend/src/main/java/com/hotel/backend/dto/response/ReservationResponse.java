package com.hotel.backend.dto.response;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RefundDestinationStatus;
import com.hotel.backend.constant.RefundRoute;
import com.hotel.backend.constant.PaymentPlan;
import com.hotel.backend.constant.ReservationCancellationReasonCode;
import com.hotel.backend.entity.Reservation;
import lombok.*;
 
import java.math.BigDecimal;

import java.time.LocalDateTime;
import java.util.List;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationResponse {
 
    private Long id;
    private String reservationCode;
    private String guestToken;
    private Long customerId;
    private Long customerProfileId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private LocalDateTime actualCheckIn;
    private LocalDateTime actualCheckOut;
    private BigDecimal totalAmount;
    private BigDecimal plannedTotalAmount;
    private BigDecimal paidAmount;
    private PaymentPlan paymentPlan;
    private BigDecimal requiredInitialPayment;
    private BigDecimal lateCheckoutFee;
    private BigDecimal earlyCheckoutAdjustment;
    private BigDecimal checkoutAdditionalFee;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private ReservationStatus status;
    private String note;
    private String cancellationReason;
    private Boolean cancellationRefundPending;
    private BigDecimal cancellationFee;
    private BigDecimal refundableAmount;
    @Builder.Default
    private RefundRoute refundRoute = RefundRoute.NONE;
    @Builder.Default
    private RefundDestinationStatus refundDestinationStatus = RefundDestinationStatus.NOT_REQUIRED;
    private String refundBankSummary;
    @Builder.Default
    private List<ReservationRefundResponse> refunds = List.of();
    private Integer guestCount;
    private Boolean noShowEligible;
    private LocalDateTime noShowEligibleAt;
    private LocalDateTime createdAt;
    private List<ReservationRoomTypeResponse> roomTypes;
    @Builder.Default
    private List<ReservationRoomResponse> rooms = List.of();
 
    public static ReservationResponse from(Reservation r) {
        return ReservationResponse.builder()
                .id(r.getId())
                .reservationCode(r.getReservationCode())
                .customerId(r.getCustomerProfile().getLinkedUser() != null
                        ? r.getCustomerProfile().getLinkedUser().getId()
                        : null)
                .customerProfileId(r.getCustomerProfile().getId())
                .customerName(r.getCustomerProfile().getFullName())
                .customerPhone(r.getCustomerProfile().getPhone())
                .customerEmail(r.getCustomerProfile().getEmail())
                .checkIn(r.getCheckIn())
                .checkOut(r.getCheckOut())
                .actualCheckIn(r.getActualCheckIn())
                .actualCheckOut(r.getActualCheckOut())
                .totalAmount(r.getTotalAmount())
                .paymentPlan(r.getPaymentPlan())
                .requiredInitialPayment(r.getRequiredInitialPayment())
                .plannedTotalAmount(r.getTotalAmount()
                        .add(r.getEarlyCheckoutAdjustment() != null ? r.getEarlyCheckoutAdjustment() : BigDecimal.ZERO)
                        .subtract(r.getLateCheckoutFee() != null ? r.getLateCheckoutFee() : BigDecimal.ZERO)
                        .subtract(r.getCheckoutAdditionalFee() != null ? r.getCheckoutAdditionalFee() : BigDecimal.ZERO))
                .lateCheckoutFee(r.getLateCheckoutFee())
                .earlyCheckoutAdjustment(r.getEarlyCheckoutAdjustment())
                .checkoutAdditionalFee(r.getCheckoutAdditionalFee())
                .discountAmount(r.getDiscountAmount())
                .taxAmount(r.getTaxAmount())
                .status(r.getStatus())
                .note(r.getNote())
                .cancellationReason(r.getCancellationReason())
                .cancellationRefundPending(
                        ReservationCancellationReasonCode.isRefundPending(
                                r.getCancellationReasonCode()))
                .cancellationFee(r.getCancellationFee())
                .refundableAmount(r.getRefundableAmount())
                .guestCount(r.getGuestCount())
                .createdAt(r.getCreatedAt())
                .build();
    }
 
    public static ReservationResponse fromWithDetails(Reservation r,
                                                      List<ReservationRoomTypeResponse> roomTypes) {
        ReservationResponse res = from(r);
        res.setRoomTypes(roomTypes);
        return res;
    }
}
