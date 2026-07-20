package com.hotel.backend.repository;

import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {

    Optional<PaymentTransaction> findByTxnRef(String txnRef);

    @Query("SELECT pt.reservation.id FROM PaymentTransaction pt WHERE pt.txnRef = :txnRef")
    Optional<Long> findReservationIdByTxnRef(@Param("txnRef") String txnRef);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pt FROM PaymentTransaction pt JOIN FETCH pt.reservation WHERE pt.txnRef = :txnRef")
    Optional<PaymentTransaction> findByTxnRefForUpdate(@Param("txnRef") String txnRef);
    Optional<PaymentTransaction> findByProviderTxnId(String providerTxnId);
    Optional<PaymentTransaction> findByProviderAndProviderTxnId(
            com.hotel.backend.constant.PaymentProvider provider,
            String providerTxnId);
    Optional<PaymentTransaction> findByProviderAndProviderReference(
            com.hotel.backend.constant.PaymentProvider provider,
            String providerReference);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pt FROM PaymentTransaction pt JOIN FETCH pt.reservation WHERE pt.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") String id);
    
    @Query("SELECT pt FROM PaymentTransaction pt WHERE pt.reservation.id = :reservationId")
    List<PaymentTransaction> findByReservationId(@Param("reservationId") Long reservationId);
 
    @Query("""
        SELECT pt FROM PaymentTransaction pt
        WHERE pt.reservation.id = :reservationId
        AND pt.status = :status
    """)
    List<PaymentTransaction> findByReservationIdAndStatus(
            @Param("reservationId") Long reservationId,
            @Param("status") PaymentStatus status);
 
    @Query("""
        SELECT CASE WHEN COUNT(pt) > 0 THEN true ELSE false END
        FROM PaymentTransaction pt
        WHERE pt.reservation.id = :reservationId
        AND pt.status = :status
    """)
    boolean existsByReservationIdAndStatus(
            @Param("reservationId") Long reservationId,
            @Param("status") PaymentStatus status);

    boolean existsByReservationIdAndPurposeAndStatus(
            Long reservationId,
            PaymentPurpose purpose,
            PaymentStatus status);

    @Query("""
        SELECT pt.id
        FROM PaymentTransaction pt
        WHERE pt.status = :pendingStatus
          AND ((pt.expiresAt IS NOT NULL AND pt.expiresAt <= :now)
               OR (pt.expiresAt IS NULL AND pt.createdAt <= :legacyCutoff))
        ORDER BY pt.id
    """)
    List<String> findExpiredPendingIds(
            @Param("pendingStatus") PaymentStatus pendingStatus,
            @Param("now") LocalDateTime now,
            @Param("legacyCutoff") LocalDateTime legacyCutoff);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT pt
        FROM PaymentTransaction pt
        WHERE pt.reservation.id = :reservationId
          AND pt.purpose = :purpose
          AND pt.status = :status
        ORDER BY pt.id
    """)
    List<PaymentTransaction> findByReservationPurposeStatusForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("purpose") PaymentPurpose purpose,
            @Param("status") PaymentStatus status);

    List<PaymentTransaction> findByStatusOrderByUpdatedAtAsc(PaymentStatus status);

    @Query("""
        SELECT pt FROM PaymentTransaction pt
        JOIN FETCH pt.reservation
        WHERE pt.provider = :provider
          AND pt.status IN :statuses
        ORDER BY pt.createdAt DESC, pt.id DESC
    """)
    List<PaymentTransaction> findManualRecoveryCandidates(
            @Param("provider") com.hotel.backend.constant.PaymentProvider provider,
            @Param("statuses") List<PaymentStatus> statuses,
            Pageable pageable);

    @Query("""
        SELECT CASE WHEN COUNT(pt) > 0 THEN true ELSE false END
        FROM PaymentTransaction pt
        WHERE pt.reservation.id = :reservationId
        AND pt.status IN :statuses
    """)
    boolean existsByReservationIdAndStatusIn(
            @Param("reservationId") Long reservationId,
            @Param("statuses") List<PaymentStatus> statuses);


     @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM PaymentTransaction p
        WHERE p.reservation.id = :reservationId
          AND p.status = com.hotel.backend.constant.PaymentStatus.SUCCESS
    """)
    Long sumSuccessAmountByReservationId(@Param("reservationId") Long reservationId);


     @Query("""
        SELECT CASE
                 WHEN COALESCE(SUM(p.amount),0) >= :requiredDeposit
                 THEN true
                 ELSE false
               END
        FROM PaymentTransaction p
        WHERE p.reservation.id = :reservationId
          AND p.status = com.hotel.backend.constant.PaymentStatus.SUCCESS
    """)
    boolean hasPaidEnough(
            @Param("reservationId") Long reservationId,
            @Param("requiredDeposit") Long requiredDeposit);


     @Query("""
        SELECT CASE
                 WHEN COALESCE(SUM(p.amount),0) >= r.totalAmount
                 THEN true
                 ELSE false
               END
        FROM Reservation r
        LEFT JOIN PaymentTransaction p
               ON p.reservation.id = r.id
              AND p.status = com.hotel.backend.constant.PaymentStatus.SUCCESS
        WHERE r.id = :reservationId
        GROUP BY r.totalAmount
    """)
    boolean isFullyPaid(@Param("reservationId") Long reservationId);

    @Query("""
        SELECT pt FROM PaymentTransaction pt
        JOIN FETCH pt.reservation r
        WHERE pt.status = :status
          AND pt.updatedAt <= :cutoff
    """)
    List<PaymentTransaction> findStaleRefundPendingTransactions(
            @Param("status") PaymentStatus status,
            @Param("cutoff") LocalDateTime cutoff);

}
