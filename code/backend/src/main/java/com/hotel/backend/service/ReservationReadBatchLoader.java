package com.hotel.backend.service;

import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.RefundRecipient;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRateSnapshot;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.ReservationServiceOrder;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads all secondary reservation read-model data with a fixed number of
 * queries. List endpoints must use this loader instead of querying add-ons,
 * pricing snapshots, payments and refunds once for every reservation row.
 */
@Component
@RequiredArgsConstructor
public class ReservationReadBatchLoader {

    private final ReservationRoomTypeRepository roomTypeRepository;
    private final ReservationServiceOrderRepository serviceOrderRepository;
    private final ReservationRateSnapshotRepository snapshotRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRefundRepository refundRepository;
    private final RefundRecipientRepository recipientRepository;

    @Transactional(readOnly = true)
    public BatchData load(List<Reservation> reservations) {
        List<Long> reservationIds = reservations == null
                ? List.of()
                : reservations.stream()
                .filter(reservation -> reservation != null
                        && reservation.getId() != null)
                .map(Reservation::getId)
                .distinct()
                .toList();
        if (reservationIds.isEmpty()) {
            return BatchData.empty();
        }

        Map<Long, List<ReservationRoomType>> roomTypes = group(
                roomTypeRepository.findDetailsByReservationIds(reservationIds),
                line -> line.getReservation().getId());
        Map<Long, List<ReservationServiceOrder>> services = group(
                serviceOrderRepository.findDetailedByReservationIds(reservationIds),
                order -> order.getReservation().getId());
        Map<Long, List<ReservationRateSnapshot>> snapshots = group(
                snapshotRepository
                        .findByReservationIdsOrderByReservationLineAndSequence(
                                reservationIds),
                snapshot -> snapshot.getReservationRoomType()
                        .getReservation().getId());
        Map<Long, List<PaymentTransaction>> payments = group(
                transactionRepository.findByReservationIds(reservationIds),
                payment -> payment.getReservation().getId());
        Map<Long, List<PaymentRefund>> refunds = group(
                refundRepository.findByReservationIds(reservationIds),
                ReservationReadBatchLoader::reservationIdOf);
        Map<Long, List<RefundRecipient>> recipients = group(
                recipientRepository
                        .findByReservationIdsAndStatusInOrderByCreatedAtDesc(
                                reservationIds,
                                EnumSet.of(RefundRecipientStatus.SUBMITTED,
                                        RefundRecipientStatus.VERIFIED)),
                recipient -> recipient.getReservation().getId());

        return new BatchData(roomTypes, services, snapshots,
                payments, refunds, recipients);
    }

    private static Long reservationIdOf(PaymentRefund refund) {
        if (refund.getReservation() != null) {
            return refund.getReservation().getId();
        }
        if (refund.getPaymentTransaction() != null
                && refund.getPaymentTransaction().getReservation() != null) {
            return refund.getPaymentTransaction().getReservation().getId();
        }
        return null;
    }

    private static <T> Map<Long, List<T>> group(
            Collection<T> values,
            Function<T, Long> reservationId) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        if (values == null) {
            return grouped;
        }
        for (T value : values) {
            Long id = reservationId.apply(value);
            if (id != null) {
                grouped.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(value);
            }
        }
        return grouped;
    }

    public record BatchData(
            Map<Long, List<ReservationRoomType>> roomTypes,
            Map<Long, List<ReservationServiceOrder>> services,
            Map<Long, List<ReservationRateSnapshot>> snapshots,
            Map<Long, List<PaymentTransaction>> payments,
            Map<Long, List<PaymentRefund>> refunds,
            Map<Long, List<RefundRecipient>> recipients) {

        private static BatchData empty() {
            return new BatchData(Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of());
        }

        public List<ReservationRoomType> roomTypesFor(Long reservationId) {
            return roomTypes.getOrDefault(reservationId, List.of());
        }

        public List<ReservationServiceOrder> servicesFor(Long reservationId) {
            return services.getOrDefault(reservationId, List.of());
        }

        public List<ReservationRateSnapshot> snapshotsFor(Long reservationId) {
            return snapshots.getOrDefault(reservationId, List.of());
        }

        public List<PaymentTransaction> paymentsFor(Long reservationId) {
            return payments.getOrDefault(reservationId, List.of());
        }

        public List<PaymentRefund> refundsFor(Long reservationId) {
            return refunds.getOrDefault(reservationId, List.of());
        }

        public List<RefundRecipient> recipientsFor(Long reservationId) {
            return recipients.getOrDefault(reservationId, List.of());
        }
    }
}
