package com.hotel.backend.scheduled;

import com.hotel.backend.repository.RoomHoldRepository;
import com.hotel.backend.service.PaymentSessionExpiryService;
import com.hotel.backend.service.BusinessMetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j(topic = "ROOM_HOLD_SCHEDULER")
@Component
@RequiredArgsConstructor
public class RoomHoldExpiryScheduler {

    private final RoomHoldRepository roomHoldRepository;
    private final PaymentSessionExpiryService paymentSessionExpiryService;
    private final BusinessMetricService businessMetrics;

    /**
     * Chạy mỗi 2 phút, expire hold đã hết hạn
     * và tự động hủy phiên PAYMENT_PENDING chưa thanh toán.
     * DRAFT là đơn đã đặt cọc, phải chờ staff xác nhận và không được tự hủy.
     */
    @Scheduled(
            fixedDelayString = "${app.maintenance.room-hold-interval-ms:120000}",
            initialDelayString = "${app.maintenance.startup-delay-ms:60000}")
    public void expireHolds() {
        LocalDateTime now = LocalDateTime.now();
        int cancelledReservations = 0;

        // Avoid bulk hold-first updates: they can interleave with a successful
        // payment-provider callback. Lock each reservation first, then mutate its holds,
        // matching the payment/callback aggregate lock order.
        List<Long> reservationIds = roomHoldRepository.findReservationIdsWithExpiredActiveHolds(now);
        for (Long reservationId : reservationIds) {
            try {
                if (paymentSessionExpiryService.timeoutDepositReservation(reservationId)) {
                    cancelledReservations++;
                    businessMetrics.increment("hotel.room.hold.auto.expired");
                }
            } catch (RuntimeException exception) {
                businessMetrics.increment(
                        "hotel.scheduler.failures", "job", "room_hold_expiry");
                log.error("Cannot expire deposit aggregate reservationId={}: {}",
                        reservationId, exception.getMessage());
            }
        }

        if (cancelledReservations > 0) {
            log.info("Expired {} unpaid deposit aggregates", cancelledReservations);
        }
        businessMetrics.increment("hotel.scheduler.runs", "job", "room_hold_expiry");
    }
}
