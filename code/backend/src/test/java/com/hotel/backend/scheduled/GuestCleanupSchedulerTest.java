package com.hotel.backend.scheduled;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.repository.GuestRepository;
import com.hotel.backend.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuestCleanupSchedulerTest {

    @Mock
    private GuestRepository guestRepository;
    @Mock
    private ReservationRepository reservationRepository;

    private GuestCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GuestCleanupScheduler(guestRepository, reservationRepository);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 90);
    }

    @Test
    void removesNoShowGuestDataOnlyAfterTheRetentionCutoff() {
        when(guestRepository.findExpiredNoShowGuestIds(any(LocalDateTime.class)))
                .thenReturn(List.of(11L, 12L));

        scheduler.cleanupExpiredGuests();

        verify(guestRepository).bulkDeleteExpiredGuests(any(LocalDateTime.class));
        verify(guestRepository).deleteAllByIdInBatch(List.of(11L, 12L));
    }

    @Test
    void revokesGuestLinksForTerminalReservationsOnlyWhenNoRefundIsActive() {
        scheduler.nullifyExpiredGuestTokens();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationStatus>> statuses = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RefundStatus>> refundStatuses = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).bulkNullifyGuestTokensByStatusAndNoActiveRefund(
                statuses.capture(), refundStatuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                ReservationStatus.CHECKED_OUT,
                ReservationStatus.CANCELLED,
                ReservationStatus.NO_SHOW);
        assertThat(refundStatuses.getValue()).containsExactlyInAnyOrder(
                RefundStatus.AWAITING_CUSTOMER_INFO,
                RefundStatus.READY_FOR_MANUAL_TRANSFER,
                RefundStatus.REQUESTED,
                RefundStatus.PROCESSING,
                RefundStatus.FAILED,
                RefundStatus.MANUAL_REVIEW);
    }
}
