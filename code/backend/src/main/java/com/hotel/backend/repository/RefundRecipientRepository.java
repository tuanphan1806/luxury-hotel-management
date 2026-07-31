package com.hotel.backend.repository;

import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.entity.RefundRecipient;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRecipientRepository extends JpaRepository<RefundRecipient, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundRecipient r WHERE r.reservation.id = :reservationId AND r.status IN :statuses")
    List<RefundRecipient> findByReservationIdAndStatusInForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("statuses") Collection<RefundRecipientStatus> statuses);

    Optional<RefundRecipient> findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
            Long reservationId,
            Collection<RefundRecipientStatus> statuses);

    @Query("""
        SELECT recipient
        FROM RefundRecipient recipient
        JOIN FETCH recipient.reservation reservation
        WHERE reservation.id IN :reservationIds
          AND recipient.status IN :statuses
        ORDER BY reservation.id, recipient.createdAt DESC, recipient.id
    """)
    List<RefundRecipient> findByReservationIdsAndStatusInOrderByCreatedAtDesc(
            @Param("reservationIds") Collection<Long> reservationIds,
            @Param("statuses") Collection<RefundRecipientStatus> statuses);

    Optional<RefundRecipient> findByPaymentRefundId(String paymentRefundId);
}
