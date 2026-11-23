package com.hotel.backend.repository;

import com.hotel.backend.constant.ReservationStatus;
import java.time.LocalDateTime;

/** One reservation line, repeated only for distinct rooms explicitly assigned in advance. */
public interface RoomDemandPeriod {
    Long getLineId();
    Long getRoomTypeId();
    Long getAssignedRoomId();
    LocalDateTime getStartsAt();
    LocalDateTime getEndsAt();
    int getQuantity();
    ReservationStatus getReservationStatus();
}
