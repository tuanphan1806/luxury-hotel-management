package com.hotel.backend.service;

import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class PricingService {

    private final BigDecimal extraHourFee;
    private final BigDecimal lateCheckoutFeePerRoomHour;

    public PricingService(
            @Value("${hotel.pricing.extra-hour-fee:10000}") BigDecimal extraHourFee,
            @Value("${hotel.pricing.late-checkout-fee-per-room-hour:10000}") BigDecimal lateCheckoutFeePerRoomHour) {
        this.extraHourFee = extraHourFee;
        this.lateCheckoutFeePerRoomHour = lateCheckoutFeePerRoomHour;
    }

    public long billableHours(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new AppException(ErrorCode.RESERVATION_INVALID_DATE);
        }
        long minutes = ChronoUnit.MINUTES.between(checkIn, checkOut);
        return Math.max(1L, (minutes + 59L) / 60L);
    }

    public BigDecimal calculateStayPricePerRoom(RoomType roomType, LocalDateTime checkIn, LocalDateTime checkOut) {
        return calculatePricePerRoom(roomType.getPrice(), billableHours(checkIn, checkOut));
    }

    public BigDecimal calculatePricePerRoom(BigDecimal firstHourPrice, long hours) {
        return firstHourPrice.add(extraHourFee.multiply(BigDecimal.valueOf(Math.max(0L, hours - 1L))));
    }

    public BigDecimal recoverFirstHourPrice(BigDecimal snapshotPrice, long bookedHours) {
        return snapshotPrice.subtract(extraHourFee.multiply(BigDecimal.valueOf(Math.max(0L, bookedHours - 1L))))
                .max(BigDecimal.ZERO);
    }

    public BigDecimal calculateLateCheckoutFee(long lateHours, int roomCount) {
        return lateCheckoutFeePerRoomHour
                .multiply(BigDecimal.valueOf(Math.max(0L, lateHours)))
                .multiply(BigDecimal.valueOf(Math.max(1, roomCount)));
    }
}
