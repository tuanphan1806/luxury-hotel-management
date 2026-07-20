package com.hotel.backend.scheduled;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.repository.GuestRepository;
import com.hotel.backend.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
 
import java.time.LocalDateTime;
import java.util.List;
 
@Slf4j(topic = "GUEST_CLEANUP_SCHEDULER")
@Component
@RequiredArgsConstructor
public class GuestCleanupScheduler {
 
    private final GuestRepository guestRepository;
    private final ReservationRepository reservationRepository;
 
    @Value("${hotel.guest-retention-days:90}")
    private int retentionDays;
 
    // Chạy mỗi ngày lúc 2h sáng
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredGuests() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = guestRepository.bulkDeleteExpiredGuests(cutoff);
        List<Long> noShowGuestIds = guestRepository.findExpiredNoShowGuestIds(cutoff);
        if (!noShowGuestIds.isEmpty()) {
            guestRepository.deleteAllByIdInBatch(noShowGuestIds);
            deleted += noShowGuestIds.size();
        }
        if (deleted > 0) {
            log.info("Deleted {} expired guest records (completed/no-show before {})", deleted, cutoff);
        }
    }

    // Chạy mỗi ngày lúc 3h sáng để thu hồi link guest sau khi booking đã kết thúc
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional(rollbackFor = Exception.class)
    public void nullifyExpiredGuestTokens() {
        int updated = reservationRepository.bulkNullifyGuestTokensByStatusAndNoActiveRefund(
                List.of(
                        ReservationStatus.CHECKED_OUT,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.NO_SHOW),
                List.of(
                        RefundStatus.AWAITING_CUSTOMER_INFO,
                        RefundStatus.READY_FOR_MANUAL_TRANSFER,
                        RefundStatus.REQUESTED,
                        RefundStatus.PROCESSING,
                        RefundStatus.FAILED,
                        RefundStatus.MANUAL_REVIEW));
        if (updated > 0) {
            log.info("Nullified guest tokens for {} completed/cancelled reservations without active refunds", updated);
        }
    }
}
