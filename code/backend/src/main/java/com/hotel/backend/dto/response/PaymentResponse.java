package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentPurpose;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.Instant;
import com.hotel.backend.entity.PaymentTransaction;

@Data
@Builder
public class PaymentResponse {
    private String transactionId;   // ID giao dịch nội bộ
    private Long bookingId;
    private String reservationCode;
    private String transactionReference;
    private String providerTransactionId;
    private PaymentProvider provider;
    private PaymentStatus status;
    private PaymentPurpose purpose;
    private Long amount;
    private Long expectedAmount;
    private Long receivedAmount;
    private Long acceptedAmount;
    private Long refundRequiredAmount;
    private Long refundAmount;
    private PaymentProvider refundProvider;
    private String requestedBankCode;
    private String bankCode;
    private String cardType;
    private String responseCode;
    private String providerPayDate;
    private String paymentUrl;      // URL redirect sang cổng thanh toán
    private String qrCodeUrl;
    private String transferContent;
    private String bankAccountNumber;
    private String bankName;
    private String accountHolder;
    private LocalDateTime expiresAt;
    private Instant expiresAtUtc;
    private Instant paidAtUtc;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentResponse from(PaymentTransaction transaction) {
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .reservationCode(transaction.getReservation().getReservationCode())
                .transactionReference(transaction.getTxnRef())
                .providerTransactionId(transaction.getProviderTxnId())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .purpose(transaction.getPurpose())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .refundAmount(transaction.getRefundAmount())
                .refundProvider(transaction.getRefundProvider())
                .requestedBankCode(transaction.getRequestedBankCode())
                .bankCode(transaction.getBankCode())
                .cardType(transaction.getCardType())
                .responseCode(transaction.getResponseCode())
                .providerPayDate(transaction.getProviderPayDate())
                .transferContent(transaction.getProvider() == PaymentProvider.SEPAY
                        ? transaction.getTxnRef() : null)
                .expiresAt(transaction.getExpiresAt())
                .expiresAtUtc(transaction.getExpiresAtUtc())
                .paidAtUtc(transaction.getPaidAtUtc())
                .message(transaction.getMessage())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }
}
