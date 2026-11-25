package com.hotel.backend.service;

import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoomType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Replays the legacy booked per-room price; issued room folios keep their actual departure price. */
@Service
@RequiredArgsConstructor
public class LegacyRoomDeparturePricing {
    private final PricingService rates;
    public record RoomAmount(BigDecimal base, BigDecimal late) {}
    public record Total(BigDecimal planned, BigDecimal actualBase, BigDecimal early, BigDecimal late, BigDecimal total) {}

    public static boolean isMultiRoom(Reservation reservation) {
        return reservation.getRoomTypes().stream().mapToInt(ReservationRoomType::getQuantity).sum() > 1;
    }

    public RoomAmount active(Reservation r, ReservationRoomType line, LocalDateTime requested) {
        LocalDateTime end = r.getActualCheckOut() != null ? r.getActualCheckOut() : requested;
        BigDecimal base = line.getRoomPrice();
        BigDecimal late = BigDecimal.ZERO;
        if (end.isAfter(r.getCheckOut())) {
            long minutes = ChronoUnit.MINUTES.between(r.getCheckOut(), end);
            late = rates.calculateLateCheckoutFee(Math.max(1, (minutes + 59) / 60), 1);
        } else if (end.isBefore(r.getCheckOut()) && r.getActualCheckIn() != null) {
            LocalDateTime start = r.getActualCheckIn();
            long plan = rates.billableHours(r.getCheckIn(), r.getCheckOut());
            long used = rates.billableHours(start, end.isAfter(start) ? end : start.plusNanos(1));
            base = rates.calculatePricePerRoom(rates.recoverFirstHourPrice(base, plan), used).min(base);
        }
        return new RoomAmount(base, late);
    }

    public RoomAmount lineTotal(Reservation r, ReservationRoomType line, LocalDateTime at) {
        var current = active(r, line, at);
        int remaining = line.getQuantity();
        BigDecimal base = BigDecimal.ZERO, late = BigDecimal.ZERO;
        for (var slot : line.getRooms()) {
            var left = slot.getDeparture();
            if (left == null) continue;
            remaining--;
            late = late.add(left.getIncludedLateFee());
            base = base.add(left.getRoomCharge().subtract(left.getIncludedLateFee()));
        }
        return new RoomAmount(base.add(current.base().multiply(BigDecimal.valueOf(remaining))),
                late.add(current.late().multiply(BigDecimal.valueOf(remaining))));
    }

    public Total project(Reservation r, LocalDateTime at) {
        BigDecimal planned = BigDecimal.ZERO, actual = BigDecimal.ZERO, late = BigDecimal.ZERO;
        for (var line : r.getRoomTypes()) {
            planned = planned.add(line.getRoomPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
            var charge = lineTotal(r, line, at);
            actual = actual.add(charge.base());
            late = late.add(charge.late());
        }
        BigDecimal beforeEarly = r.getTotalAmount().add(zero(r.getEarlyCheckoutAdjustment()))
                .subtract(zero(r.getLateCheckoutFee())).add(late);
        // A discount can use up the pretax balance. Only persist the reduction actually applied so
        // repeated quotes, returning to the planned checkout time, and later room charges replay correctly.
        BigDecimal available = beforeEarly.subtract(zero(r.getTaxAmount())).max(BigDecimal.ZERO);
        BigDecimal early = planned.subtract(actual).max(BigDecimal.ZERO).min(available);
        BigDecimal total = beforeEarly.subtract(early).max(BigDecimal.ZERO);
        return new Total(planned, actual, early, late, total);
    }

    private BigDecimal zero(BigDecimal n) { return n == null ? BigDecimal.ZERO : n; }
}
