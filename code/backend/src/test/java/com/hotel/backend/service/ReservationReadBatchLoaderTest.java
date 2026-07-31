package com.hotel.backend.service;

import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationReadBatchLoaderTest {

    @Mock ReservationRoomTypeRepository roomTypeRepository;
    @Mock ReservationServiceOrderRepository serviceOrderRepository;
    @Mock ReservationRateSnapshotRepository snapshotRepository;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock PaymentRefundRepository refundRepository;
    @Mock RefundRecipientRepository recipientRepository;

    private ReservationReadBatchLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ReservationReadBatchLoader(
                roomTypeRepository,
                serviceOrderRepository,
                snapshotRepository,
                transactionRepository,
                refundRepository,
                recipientRepository);
    }

    @Test
    void loadsEachSecondaryReadModelOnceForTheWholeReservationList() {
        Reservation first = reservation(11L);
        Reservation duplicate = reservation(11L);
        Reservation second = reservation(12L);
        List<Long> ids = List.of(11L, 12L);

        when(roomTypeRepository.findDetailsByReservationIds(ids)).thenReturn(List.of());
        when(serviceOrderRepository.findDetailedByReservationIds(ids)).thenReturn(List.of());
        when(snapshotRepository.findByReservationIdsOrderByReservationLineAndSequence(ids))
                .thenReturn(List.of());
        when(transactionRepository.findByReservationIds(ids)).thenReturn(List.of());
        when(refundRepository.findByReservationIds(ids)).thenReturn(List.of());
        when(recipientRepository.findByReservationIdsAndStatusInOrderByCreatedAtDesc(
                eq(ids), any())).thenReturn(List.of());

        ReservationReadBatchLoader.BatchData batch = loader.load(
                List.of(first, duplicate, second));

        assertThat(batch.roomTypesFor(11L)).isEmpty();
        assertThat(batch.paymentsFor(12L)).isEmpty();
        verify(roomTypeRepository).findDetailsByReservationIds(ids);
        verify(serviceOrderRepository).findDetailedByReservationIds(ids);
        verify(snapshotRepository).findByReservationIdsOrderByReservationLineAndSequence(ids);
        verify(transactionRepository).findByReservationIds(ids);
        verify(refundRepository).findByReservationIds(ids);
        verify(recipientRepository)
                .findByReservationIdsAndStatusInOrderByCreatedAtDesc(eq(ids), any());
    }

    @Test
    void skipsRepositoriesForAnEmptyReservationList() {
        ReservationReadBatchLoader.BatchData batch = loader.load(List.of());

        assertThat(batch.servicesFor(1L)).isEmpty();
        verifyNoInteractions(roomTypeRepository, serviceOrderRepository,
                snapshotRepository, transactionRepository, refundRepository,
                recipientRepository);
    }

    private Reservation reservation(Long id) {
        Reservation reservation = Reservation.builder().build();
        reservation.setId(id);
        return reservation;
    }
}
