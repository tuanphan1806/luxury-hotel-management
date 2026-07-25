package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.SePayPaymentInstructions;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaymentResponseMapperTest {

    private final PaymentResponseMapper mapper = new PaymentResponseMapper();

    @Test
    void toResponsePreservesTheExistingGenericPaymentContract() {
        PaymentTransaction transaction = transaction();
        transaction.setRefundProvider(PaymentProvider.CASH);
        transaction.setRefundAmount(10_000L);

        PaymentResponse response = mapper.toResponse(transaction);

        assertEquals("payment-1", response.getTransactionId());
        assertEquals(41L, response.getBookingId());
        assertEquals("RES-41", response.getReservationCode());
        assertEquals(PaymentProvider.SEPAY, response.getProvider());
        assertEquals(PaymentProvider.CASH, response.getRefundProvider());
        assertEquals(10_000L, response.getRefundAmount());
        assertEquals(transaction.getExpiresAtUtc(), response.getExpiresAtUtc());
        assertNull(response.getTransactionReference());
        assertNull(response.getQrCodeUrl());
    }

    @Test
    void toSePayCreateResponseAddsOnlyTheExistingTransferInstructions() {
        PaymentTransaction transaction = transaction();
        LocalDateTime instructionExpiry = LocalDateTime.of(2026, 7, 23, 23, 30);
        SePayPaymentInstructions instructions = new SePayPaymentInstructions(
                "https://qr.example/LP41",
                "LP41",
                "10004712857",
                "970423",
                "TPBank",
                "PHAN VIET ANH TUAN",
                50_000L,
                instructionExpiry);

        PaymentResponse response = mapper.toSePayCreateResponse(
                transaction, instructions, "Đã tạo mã SePay VietQR");

        assertEquals("LP41", response.getTransactionReference());
        assertEquals("https://qr.example/LP41", response.getQrCodeUrl());
        assertEquals("LP41", response.getTransferContent());
        assertEquals("10004712857", response.getBankAccountNumber());
        assertEquals("970423", response.getBankCode());
        assertEquals("TPBank", response.getBankName());
        assertEquals("PHAN VIET ANH TUAN", response.getAccountHolder());
        assertEquals(instructionExpiry, response.getExpiresAt());
        assertEquals("Đã tạo mã SePay VietQR", response.getMessage());
        assertNull(response.getRefundAmount());
    }

    private PaymentTransaction transaction() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-41")
                .build();
        reservation.setId(41L);
        return PaymentTransaction.builder()
                .id("payment-1")
                .reservation(reservation)
                .txnRef("LP41")
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.PENDING)
                .amount(50_000L)
                .expectedAmount(50_000L)
                .receivedAmount(0L)
                .acceptedAmount(0L)
                .refundRequiredAmount(0L)
                .message("pending")
                .expiresAt(LocalDateTime.of(2026, 7, 23, 23, 30))
                .expiresAtUtc(Instant.parse("2026-07-23T16:30:00Z"))
                .paidAtUtc(null)
                .createdAt(LocalDateTime.of(2026, 7, 23, 23, 20))
                .updatedAt(LocalDateTime.of(2026, 7, 23, 23, 21))
                .build();
    }
}
