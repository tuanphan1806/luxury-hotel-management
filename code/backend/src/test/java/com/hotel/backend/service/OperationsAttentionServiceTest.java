package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.OperationsAttentionResponse;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsAttentionServiceTest {

    @Mock ReservationRepository reservationRepository;
    @Mock PaymentTransactionRepository paymentTransactionRepository;
    @InjectMocks OperationsAttentionService attentionService;

    /**
     * Hàng đợi phải chứa yêu cầu hủy, checkout quá giờ và refund đang chờ;
     * các mục DANGER luôn đứng trước WARNING/INFO dù hạn của mục nhẹ hơn sớm hơn.
     */
    @Test
    void queueIncludesCriticalOperationalCasesAndSortsBySeverity() {
        LocalDateTime now = LocalDateTime.now();
        Reservation cancellation = reservation(1L, "RES-CANCEL", ReservationStatus.CANCELLATION_PENDING,
                now.plusHours(2), now.plusHours(5));
        cancellation.setUpdatedAt(now.minusMinutes(20));
        cancellation.setRefundableAmount(BigDecimal.valueOf(50_000));

        Reservation overdueCheckout = reservation(2L, "RES-CHECKOUT", ReservationStatus.CHECKED_IN,
                now.minusHours(6), now.minusHours(3));

        PaymentTransaction pendingRefund = PaymentTransaction.builder()
                .reservation(cancellation)
                .txnRef("REFUND-QUEUE-1")
                .status(PaymentStatus.REFUND_PENDING)
                .amount(50_000L)
                .refundAmount(50_000L)
                .build();
        pendingRefund.setUpdatedAt(now.minusHours(2));

        when(reservationRepository.findAllWithDetails()).thenReturn(List.of(cancellation, overdueCheckout));
        when(paymentTransactionRepository.findByStatusOrderByUpdatedAtAsc(PaymentStatus.REFUND_PENDING))
                .thenReturn(List.of(pendingRefund));
        ReflectionTestUtils.setField(attentionService, "draftWarningMinutes", 30L);
        ReflectionTestUtils.setField(attentionService, "arrivalWindowHours", 2L);
        ReflectionTestUtils.setField(attentionService, "refundAlertHours", 48L);

        OperationsAttentionResponse result = attentionService.getQueue();

        assertEquals(3, result.getTotal());
        assertTrue(result.getItems().stream().anyMatch(item -> item.getType().equals("CANCELLATION_REQUEST")));
        assertTrue(result.getItems().stream().anyMatch(item -> item.getType().equals("CHECK_OUT_OVERDUE")));
        assertTrue(result.getItems().stream().anyMatch(item -> item.getType().equals("REFUND_PENDING")));
        assertEquals("DANGER", result.getItems().get(0).getSeverity());
        assertEquals("WARNING", result.getItems().get(result.getItems().size() - 1).getSeverity());
    }

    /**
     * Mọi đơn DRAFT phải xuất hiện ngay trong hàng đợi để lễ tân biết mã đơn nào đang chờ xác nhận.
     * Đơn còn trong thời gian xử lý là INFO; quá 30 phút mới được nâng lên WARNING.
     */
    @Test
    void queueIncludesFreshAndOverdueDraftReservations() {
        LocalDateTime now = LocalDateTime.now();
        Reservation freshDraft = reservation(3L, "RES-DRAFT-NEW", ReservationStatus.DRAFT,
                now.plusDays(1), now.plusDays(2));
        freshDraft.setCreatedAt(now.minusMinutes(5));
        Reservation overdueDraft = reservation(4L, "RES-DRAFT-LATE", ReservationStatus.DRAFT,
                now.plusDays(1), now.plusDays(2));
        overdueDraft.setCreatedAt(now.minusMinutes(45));

        when(reservationRepository.findAllWithDetails()).thenReturn(List.of(freshDraft, overdueDraft));
        when(paymentTransactionRepository.findByStatusOrderByUpdatedAtAsc(PaymentStatus.REFUND_PENDING))
                .thenReturn(List.of());
        ReflectionTestUtils.setField(attentionService, "draftWarningMinutes", 30L);
        ReflectionTestUtils.setField(attentionService, "arrivalWindowHours", 2L);
        ReflectionTestUtils.setField(attentionService, "refundAlertHours", 48L);

        OperationsAttentionResponse result = attentionService.getQueue();

        assertEquals(2, result.getTotal());
        assertTrue(result.getItems().stream().anyMatch(item -> item.getType().equals("DRAFT_PENDING")
                && item.getReservationCode().equals("RES-DRAFT-NEW") && item.getSeverity().equals("INFO")));
        assertTrue(result.getItems().stream().anyMatch(item -> item.getType().equals("DRAFT_OVERDUE")
                && item.getReservationCode().equals("RES-DRAFT-LATE") && item.getSeverity().equals("WARNING")));
    }

    private Reservation reservation(Long id, String code, ReservationStatus status,
                                    LocalDateTime checkIn, LocalDateTime checkOut) {
        Reservation reservation = Reservation.builder()
                .reservationCode(code)
                .customerProfile(CustomerProfile.builder().fullName("Khách kiểm thử").build())
                .status(status)
                .checkIn(checkIn)
                .checkOut(checkOut)
                .totalAmount(BigDecimal.valueOf(100_000))
                .build();
        reservation.setId(id);
        reservation.setCreatedAt(LocalDateTime.now().minusHours(1));
        return reservation;
    }
}
