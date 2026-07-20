package com.hotel.backend.repository;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationCode(String reservationCode);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    boolean existsByReservationCode(String reservationCode);

    boolean existsByGuestToken(String guestToken);

    @Query("""
        SELECT r FROM Reservation r
        JOIN FETCH r.customerProfile cp
        LEFT JOIN FETCH cp.linkedUser
        LEFT JOIN FETCH r.roomTypes rt
        LEFT JOIN FETCH rt.roomType
        LEFT JOIN FETCH rt.roomHold
        WHERE r.guestToken = :guestToken
    """)
    Optional<Reservation> findByGuestTokenWithDetails(@Param("guestToken") String guestToken);

    @Query("""
    SELECT DISTINCT r FROM Reservation r
    LEFT JOIN FETCH r.roomTypes rt
    LEFT JOIN FETCH rt.roomType
    LEFT JOIN FETCH rt.roomHold
    WHERE r.customerProfile.linkedUser.id = :userId
      AND r.status <> com.hotel.backend.constant.ReservationStatus.PAYMENT_PENDING
    ORDER BY r.createdAt DESC
    """)
    List<Reservation> findByLinkedUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    List<Reservation> findByStatus(ReservationStatus status);

    @Query("""
        SELECT r.id FROM Reservation r
        WHERE r.status = com.hotel.backend.constant.ReservationStatus.PAYMENT_PENDING
          AND COALESCE(r.lastActivityAt, r.createdAt) <= :cutoff
          AND NOT EXISTS (
              SELECT pt.id FROM PaymentTransaction pt
              WHERE pt.reservation = r
                AND pt.status IN (com.hotel.backend.constant.PaymentStatus.PENDING,
                                  com.hotel.backend.constant.PaymentStatus.SUCCESS)
          )
          AND NOT EXISTS (
              SELECT rh.id FROM RoomHold rh
              JOIN rh.reservationRoomType rrt
              WHERE rrt.reservation = r
                AND rh.status = com.hotel.backend.constant.HoldStatus.ACTIVE
                AND rh.expiresAt > :now
          )
        ORDER BY r.id
    """)
    List<Long> findStalePrePaymentSessionIds(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now);

    long countByStatus(ReservationStatus status);

    @Query("""
        SELECT COUNT(r) FROM Reservation r
        WHERE r.checkIn >= :start AND r.checkIn < :end
          AND r.status IN :statuses
    """)
    long countByCheckInWindowAndStatuses(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("statuses") List<ReservationStatus> statuses);

    @Query("""
        SELECT COUNT(r) FROM Reservation r
        WHERE r.checkOut >= :start AND r.checkOut < :end
          AND r.status IN :statuses
    """)
    long countByCheckOutWindowAndStatuses(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("statuses") List<ReservationStatus> statuses);

    @Query("""
        SELECT COUNT(r) FROM Reservation r
        WHERE r.createdAt >= :start AND r.createdAt < :end
          AND r.status <> :excludedStatus
    """)
    long countCreatedInWindowExcludingStatus(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("excludedStatus") ReservationStatus excludedStatus);

    long countByCustomerProfile(CustomerProfile customerProfile);

    @Modifying
    @Query("""
        UPDATE Reservation r
        SET r.customerProfile = :targetProfile
        WHERE r.customerProfile = :sourceProfile
    """)
    int reassignCustomerProfile(
            @Param("sourceProfile") CustomerProfile sourceProfile,
            @Param("targetProfile") CustomerProfile targetProfile);

    @Query("""
        SELECT r FROM Reservation r
        JOIN FETCH r.customerProfile cp
        LEFT JOIN FETCH cp.linkedUser
        JOIN FETCH r.roomTypes rt
        JOIN FETCH rt.roomType
        LEFT JOIN FETCH rt.roomHold
        WHERE r.id = :id
    """)
    Optional<Reservation> findByIdWithDetails(@Param("id") Long id);

        // Lấy tất cả (staff/admin)
    @Query("""
        SELECT DISTINCT r FROM Reservation r
        JOIN FETCH r.customerProfile cp
        LEFT JOIN FETCH cp.linkedUser
        JOIN FETCH r.roomTypes rt
        JOIN FETCH rt.roomType
        LEFT JOIN FETCH rt.roomHold
        WHERE r.status <> com.hotel.backend.constant.ReservationStatus.PAYMENT_PENDING
        ORDER BY r.createdAt DESC
    """)
    List<Reservation> findAllWithDetails();

    @Modifying
    @Query("""
        UPDATE Reservation r
        SET r.status = :cancelledStatus,
            r.cancellationReason = :reason
        WHERE r.status = :draftStatus
        AND NOT EXISTS (
            SELECT 1
            FROM ReservationRoomType rrt
            JOIN rrt.roomHold rh
            WHERE rrt.reservation = r
              AND rh.status = com.hotel.backend.constant.HoldStatus.ACTIVE
              AND rh.expiresAt > :now
        )
    """)
    int bulkCancelDraftReservationsWithoutActiveHold(
            @Param("draftStatus") ReservationStatus draftStatus,
            @Param("cancelledStatus") ReservationStatus cancelledStatus,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
        UPDATE Reservation r
        SET r.status = :draftStatus,
            r.cancellationReason = null
        WHERE r.status = :cancelledStatus
          AND r.cancellationReason = :systemReason
          AND EXISTS (
            SELECT 1 FROM PaymentTransaction pt
            WHERE pt.reservation = r
              AND pt.status = com.hotel.backend.constant.PaymentStatus.SUCCESS
          )
          AND EXISTS (
            SELECT 1 FROM ReservationRoomType rrt
            JOIN rrt.roomHold rh
            WHERE rrt.reservation = r
              AND rh.status = com.hotel.backend.constant.HoldStatus.CONVERTED
          )
    """)
    int restorePaidReservationsCancelledByExpiredHold(
            @Param("cancelledStatus") ReservationStatus cancelledStatus,
            @Param("draftStatus") ReservationStatus draftStatus,
            @Param("systemReason") String systemReason);

    @Modifying
    @Query("""
        UPDATE Reservation r
        SET r.guestToken = null
        WHERE r.guestToken IS NOT NULL
          AND r.status IN :statuses
          AND NOT EXISTS (
            SELECT pr.id FROM PaymentRefund pr
            WHERE pr.paymentTransaction.reservation = r
              AND pr.status IN :activeRefundStatuses
          )
    """)
    int bulkNullifyGuestTokensByStatusAndNoActiveRefund(
            @Param("statuses") List<ReservationStatus> statuses,
            @Param("activeRefundStatuses") List<RefundStatus> activeRefundStatuses);
}
