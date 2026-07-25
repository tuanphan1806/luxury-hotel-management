package com.hotel.backend.service;

import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.SePayPaymentInstructions;
import com.hotel.backend.entity.PaymentTransaction;
import org.springframework.stereotype.Component;

@Component
public class PaymentResponseMapper {

    public PaymentResponse toResponse(PaymentTransaction transaction) {
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .reservationCode(transaction.getReservation().getReservationCode())
                .provider(transaction.getProvider())
                .refundProvider(transaction.getRefundProvider())
                .purpose(transaction.getPurpose())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .refundAmount(transaction.getRefundAmount())
                .message(transaction.getMessage())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .expiresAt(transaction.getExpiresAt())
                .expiresAtUtc(transaction.getExpiresAtUtc())
                .paidAtUtc(transaction.getPaidAtUtc())
                .build();
    }

    public PaymentResponse toSePayCreateResponse(
            PaymentTransaction transaction,
            SePayPaymentInstructions instructions,
            String message) {
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .reservationCode(transaction.getReservation().getReservationCode())
                .transactionReference(transaction.getTxnRef())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .purpose(transaction.getPurpose())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .qrCodeUrl(instructions.qrCodeUrl())
                .transferContent(instructions.transferContent())
                .bankAccountNumber(instructions.bankAccountNumber())
                .bankCode(instructions.bankCode())
                .bankName(instructions.bankName())
                .accountHolder(instructions.accountHolder())
                .expiresAt(instructions.expiresAt())
                .expiresAtUtc(transaction.getExpiresAtUtc())
                .paidAtUtc(transaction.getPaidAtUtc())
                .message(message)
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }
}
