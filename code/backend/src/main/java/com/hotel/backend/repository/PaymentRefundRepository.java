package com.hotel.backend.repository;

import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.entity.PaymentRefund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PaymentRefund r LEFT JOIN FETCH r.paymentTransaction p LEFT JOIN FETCH p.reservation LEFT JOIN FETCH r.reservation LEFT JOIN FETCH r.recipient LEFT JOIN FETCH r.proofAsset WHERE r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") String id);

    @Query("""
        SELECT COALESCE(directReservation.id, paymentReservation.id)
        FROM PaymentRefund r
        LEFT JOIN r.reservation directReservation
        LEFT JOIN r.paymentTransaction payment
        LEFT JOIN payment.reservation paymentReservation
        WHERE r.id = :id
    """)
    Optional<Long> findAggregateReservationId(@Param("id") String id);

    @Query("""
        SELECT payment.id
        FROM PaymentRefund r
        LEFT JOIN r.paymentTransaction payment
        WHERE r.id = :id
    """)
    Optional<String> findAggregatePaymentId(@Param("id") String id);

    @Query("SELECT r FROM PaymentRefund r LEFT JOIN FETCH r.paymentTransaction p LEFT JOIN FETCH p.reservation LEFT JOIN FETCH r.reservation LEFT JOIN FETCH r.recipient LEFT JOIN FETCH r.proofAsset "
            + "WHERE r.status IN :statuses ORDER BY r.updatedAt ASC")
    List<PaymentRefund> findOperationalQueue(@Param("statuses") Collection<RefundStatus> statuses);

    List<PaymentRefund> findByPaymentTransactionId(String paymentTransactionId);

    Optional<PaymentRefund> findBySourceKey(String sourceKey);

    @Query("SELECT r FROM PaymentRefund r LEFT JOIN FETCH r.reservation LEFT JOIN FETCH r.paymentTransaction p LEFT JOIN FETCH p.reservation "
            + "LEFT JOIN FETCH r.recipient WHERE r.refundCode IN :refundCodes "
            + "AND r.channel = :channel AND r.status IN :statuses")
    List<PaymentRefund> findPendingByRefundCodes(
            @Param("refundCodes") Collection<String> refundCodes,
            @Param("channel") RefundChannel channel,
            @Param("statuses") Collection<RefundStatus> statuses);

    boolean existsByManualTransferReferenceAndIdNot(String manualTransferReference, String id);

    @Query("SELECT r FROM PaymentRefund r LEFT JOIN FETCH r.paymentTransaction p LEFT JOIN FETCH r.reservation reservation LEFT JOIN FETCH r.recipient "
            + "WHERE (reservation.id = :reservationId OR p.reservation.id = :reservationId) "
            + "AND r.channel = :channel AND r.status IN :statuses")
    List<PaymentRefund> findByReservationIdAndChannelAndStatusIn(
            @Param("reservationId") Long reservationId,
            @Param("channel") RefundChannel channel,
            @Param("statuses") Collection<RefundStatus> statuses);

    @Query("""
        SELECT COALESCE(SUM(r.amount), 0)
        FROM PaymentRefund r
        WHERE r.paymentTransaction.id = :paymentTransactionId
          AND r.status IN :statuses
    """)
    Long sumAmountByPaymentAndStatusIn(
            @Param("paymentTransactionId") String paymentTransactionId,
            @Param("statuses") Collection<RefundStatus> statuses);

    @Query("""
        SELECT r FROM PaymentRefund r
        LEFT JOIN FETCH r.paymentTransaction p
        LEFT JOIN FETCH r.reservation reservation
        LEFT JOIN FETCH r.recipient
        LEFT JOIN FETCH r.proofAsset
        WHERE reservation.id = :reservationId OR p.reservation.id = :reservationId
    """)
    List<PaymentRefund> findByReservationId(@Param("reservationId") Long reservationId);

    @Query("""
        SELECT DISTINCT r FROM PaymentRefund r
        LEFT JOIN FETCH r.paymentTransaction payment
        LEFT JOIN FETCH payment.reservation paymentReservation
        LEFT JOIN FETCH r.reservation directReservation
        LEFT JOIN FETCH r.recipient
        LEFT JOIN FETCH r.proofAsset
        WHERE directReservation.id IN :reservationIds
           OR paymentReservation.id IN :reservationIds
        ORDER BY r.createdAt, r.id
    """)
    List<PaymentRefund> findByReservationIds(
            @Param("reservationIds") Collection<Long> reservationIds);

    List<PaymentRefund> findByStatusAndCompletedAtUtcGreaterThanEqualAndCompletedAtUtcLessThan(
            RefundStatus status,
            Instant from,
            Instant to);

    @Query("""
        SELECT COUNT(refund)
        FROM PaymentRefund refund
        WHERE refund.status = :status
          AND refund.completedAtUtc >= :from
          AND refund.completedAtUtc < :to
          AND NOT EXISTS (
              SELECT journal.id
              FROM FinancialJournalEntry journal
              WHERE journal.refund.id = refund.id
          )
    """)
    long countUnpostedCompletedRefunds(
            @Param("status") RefundStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(COALESCE(refund.requestedAmount, refund.amount)), 0)
        FROM PaymentRefund refund
        WHERE refund.status IN :statuses
    """)
    Long sumOutstandingRequestedAmount(@Param("statuses") Collection<RefundStatus> statuses);
}
