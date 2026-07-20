package com.hotel.backend.integration;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.service.VNPayProviderResult;
import com.hotel.backend.service.VNPayRefundGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "vnpay.refund-enabled=true")
@ActiveProfiles("test")
class VNPayRefundAfterCommitIntegrationTest {

    @Autowired PaymentRefundService refundService;
    @Autowired PaymentRefundRepository refundRepository;
    @Autowired PaymentTransactionRepository paymentRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired CustomerProfileRepository customerProfileRepository;

    @MockitoBean
    VNPayRefundGateway gateway;

    @Test
    void newRefundForHistoricalVnPayPaymentUsesStaffSelectedBankChannel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(gateway.newRequestId()).thenReturn("RF-AFTER-COMMIT-" + suffix);

        CustomerProfile customer = customerProfileRepository.save(CustomerProfile.builder()
                .fullName("Refund integration " + suffix)
                .email("refund-" + suffix + "@example.test")
                .source(CustomerProfileSource.STAFF_CREATED)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("RES-AFTER-" + suffix)
                .customerProfile(customer)
                .checkIn(LocalDateTime.now().plusDays(1))
                .checkOut(LocalDateTime.now().plusDays(2))
                .totalAmount(BigDecimal.valueOf(100_000L))
                .guestCount(1)
                .status(ReservationStatus.CANCELLED)
                .build());
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PAY-AFTER-" + suffix)
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(100_000L)
                .currency("VND")
                .providerCreateDate("20260715120000")
                .paidAt(LocalDateTime.now())
                .build());

        String refundId = refundService.requestReservationRefund(
                        reservation.getId(), 100_000L, RefundChannel.MANUAL_BANK_TRANSFER,
                        "Test AFTER_COMMIT", "integration_test")
                .get(0).getRefundId();

        PaymentRefund persisted = refundRepository.findById(refundId).orElseThrow();
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, persisted.getChannel());
        assertEquals(RefundStatus.AWAITING_CUSTOMER_INFO, persisted.getStatus());
        assertEquals(PaymentStatus.REFUND_PENDING,
                paymentRepository.findById(payment.getId()).orElseThrow().getStatus());
        verify(gateway).newRequestId();
        verify(gateway, never()).refund(any(PaymentRefund.class));
    }

    @Test
    void queryDrCallsProviderOutsideDatabaseTransactionAndPersistsResult() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(gateway.query(any(PaymentRefund.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Không được giữ database transaction trong lúc QueryDR");
            return new VNPayProviderResult(
                    "00", "00", "02", "VNP-QUERY-" + suffix, "Hoan thanh");
        });

        CustomerProfile customer = customerProfileRepository.save(CustomerProfile.builder()
                .fullName("Query integration " + suffix)
                .email("query-" + suffix + "@example.test")
                .source(CustomerProfileSource.STAFF_CREATED)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("RES-QUERY-" + suffix)
                .customerProfile(customer)
                .checkIn(LocalDateTime.now().plusDays(1))
                .checkOut(LocalDateTime.now().plusDays(2))
                .totalAmount(BigDecimal.valueOf(80_000L))
                .guestCount(1)
                .status(ReservationStatus.CANCELLED)
                .build());
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PAY-QUERY-" + suffix)
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(80_000L)
                .currency("VND")
                .providerCreateDate("20260715130000")
                .paidAt(LocalDateTime.now())
                .build());
        PaymentRefund refund = refundRepository.save(PaymentRefund.builder()
                .paymentTransaction(payment)
                .provider(PaymentProvider.VNPAY)
                .status(RefundStatus.PROCESSING)
                .amount(80_000L)
                .requestId("RF-QUERY-" + suffix)
                .transactionType("02")
                .originalTransactionDate("20260715130000")
                .reason("QueryDR integration")
                .requestedBy("integration_test")
                .requestedAt(LocalDateTime.now())
                .build());

        var response = refundService.reconcile(refund.getId());

        assertEquals(RefundStatus.SUCCEEDED, response.getStatus());
        assertEquals("00", response.getResponseCode());
        assertEquals("VNP-QUERY-" + suffix, response.getRefundTransactionId());
        assertEquals(PaymentStatus.REFUNDED,
                paymentRepository.findById(payment.getId()).orElseThrow().getStatus());
        verify(gateway).query(any(PaymentRefund.class));
    }

    @Test
    void retryUsesANewRequestIdAndKeepsThePreviousIdForAudit() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String oldRequestId = "RF-FAILED-" + suffix;
        String newRequestId = "RF-RETRY-" + suffix;
        when(gateway.newRequestId()).thenReturn(newRequestId);
        when(gateway.refund(any(PaymentRefund.class))).thenAnswer(invocation -> {
            PaymentRefund submitted = invocation.getArgument(0);
            assertEquals(newRequestId, submitted.getRequestId());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Không được giữ database transaction trong lúc retry VNPay");
            return new VNPayProviderResult(
                    "00", "00", "02", "VNP-RETRY-" + suffix, "Hoan thanh");
        });

        CustomerProfile customer = customerProfileRepository.save(CustomerProfile.builder()
                .fullName("Retry integration " + suffix)
                .email("retry-" + suffix + "@example.test")
                .source(CustomerProfileSource.STAFF_CREATED)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("RES-RETRY-" + suffix)
                .customerProfile(customer)
                .checkIn(LocalDateTime.now().plusDays(1))
                .checkOut(LocalDateTime.now().plusDays(2))
                .totalAmount(BigDecimal.valueOf(60_000L))
                .guestCount(1)
                .status(ReservationStatus.CANCELLED)
                .build());
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PAY-RETRY-" + suffix)
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(60_000L)
                .currency("VND")
                .providerCreateDate("20260715140000")
                .paidAt(LocalDateTime.now())
                .build());
        PaymentRefund refund = refundRepository.save(PaymentRefund.builder()
                .paymentTransaction(payment)
                .provider(PaymentProvider.VNPAY)
                .status(RefundStatus.FAILED)
                .amount(60_000L)
                .requestId(oldRequestId)
                .transactionType("02")
                .originalTransactionDate("20260715140000")
                .reason("Retry integration")
                .requestedBy("integration_test")
                .requestedAt(LocalDateTime.now())
                .build());

        var response = refundService.retry(refund.getId());
        PaymentRefund persisted = refundRepository.findById(refund.getId()).orElseThrow();

        assertEquals(RefundStatus.SUCCEEDED, response.getStatus());
        assertEquals(newRequestId, persisted.getRequestId());
        assertEquals(oldRequestId, persisted.getRequestHistory());
        assertEquals(PaymentStatus.REFUNDED,
                paymentRepository.findById(payment.getId()).orElseThrow().getStatus());
        verify(gateway).refund(any(PaymentRefund.class));
    }

    @Test
    void queryDrRejectsManualReviewWithoutChangingItsState() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        CustomerProfile customer = customerProfileRepository.save(CustomerProfile.builder()
                .fullName("Manual review " + suffix)
                .email("manual-review-" + suffix + "@example.test")
                .source(CustomerProfileSource.STAFF_CREATED)
                .build());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationCode("RES-MANUAL-" + suffix)
                .customerProfile(customer)
                .checkIn(LocalDateTime.now().plusDays(1))
                .checkOut(LocalDateTime.now().plusDays(2))
                .totalAmount(BigDecimal.valueOf(70_000L))
                .guestCount(1)
                .status(ReservationStatus.CANCELLED)
                .build());
        PaymentTransaction payment = paymentRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PAY-MANUAL-" + suffix)
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(70_000L)
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .build());
        PaymentRefund refund = refundRepository.save(PaymentRefund.builder()
                .paymentTransaction(payment)
                .provider(PaymentProvider.VNPAY)
                .status(RefundStatus.MANUAL_REVIEW)
                .amount(70_000L)
                .requestId("RF-MANUAL-" + suffix)
                .transactionType("02")
                .reason("Thiếu ngày giao dịch gốc")
                .requestedBy("integration_test")
                .requestedAt(LocalDateTime.now())
                .build());

        assertThrows(RuntimeException.class, () -> refundService.reconcile(refund.getId()));

        assertEquals(RefundStatus.MANUAL_REVIEW,
                refundRepository.findById(refund.getId()).orElseThrow().getStatus());
        verifyNoInteractions(gateway);
    }

}
