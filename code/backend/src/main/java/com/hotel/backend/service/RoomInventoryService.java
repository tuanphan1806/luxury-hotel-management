package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.RoomDemandPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class RoomInventoryService {
    private final ReservationRoomTypeRepository reservationLines;

    public record Demand(int booked, int held) {
        public static final Demand NONE = new Demand(0, 0);

        public int total() { return booked + held; }
        Demand plus(Demand other) { return new Demand(booked + other.booked, held + other.held); }
    }

    /** Peak simultaneous demand over each requested stay, including unexpired payment holds. */
    public Map<Long, Demand> demand(LocalDateTime arrival, Map<Long, LocalDateTime> endsByType,
                                   Long excludeReservationId, LocalDateTime now) {
        if (endsByType.isEmpty()) return Map.of();
        LocalDateTime lastEnd = endsByType.values().stream().max(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime overdueAsOf = arrival.toLocalDate().isAfter(now.toLocalDate()) ? null : now;
        var periods = reservationLines.findInventoryPeriods(
                endsByType.keySet(), excludeReservationId, arrival, lastEnd, now, overdueAsOf);
        Map<Long, Calendar> calendars = new HashMap<>();
        for (RoomDemandPeriod period : periods) {
            LocalDateTime requestedEnd = endsByType.get(period.getRoomTypeId());
            LocalDateTime start = period.getStartsAt().isBefore(arrival) ? arrival : period.getStartsAt();
            LocalDateTime end = period.getEndsAt();
            boolean overdue = period.getReservationStatus() == ReservationStatus.CHECKED_IN
                    && overdueAsOf != null && !end.isAfter(overdueAsOf);
            if (overdue || end.isAfter(requestedEnd)) end = requestedEnd;
            if (start.isBefore(end)) {
                calendars.computeIfAbsent(period.getRoomTypeId(), id -> new Calendar()).add(period, start, end);
            }
        }
        Map<Long, Demand> result = new HashMap<>();
        calendars.forEach((typeId, calendar) -> result.put(typeId, calendar.peak()));
        return result;
    }

    private static final class Calendar {
        private final TreeMap<LocalDateTime, Demand> changes = new TreeMap<>();
        private final Set<Long> seenLines = new HashSet<>();
        private final Set<Long> preassignedRooms = new HashSet<>();

        void add(RoomDemandPeriod period, LocalDateTime start, LocalDateTime end) {
            int quantity = seenLines.add(period.getLineId()) ? period.getQuantity() : 0;
            if (period.getAssignedRoomId() != null) {
                // A guest needs one physical room for their whole stay. Keep an explicit
                // advance assignment on that room, even if another room is free later.
                preassignedRooms.add(period.getAssignedRoomId());
                quantity--;
            }
            boolean held = period.getReservationStatus() == ReservationStatus.PAYMENT_PENDING;
            changes.merge(start, held ? new Demand(0, quantity) : new Demand(quantity, 0), Demand::plus);
            changes.merge(end, held ? new Demand(0, -quantity) : new Demand(-quantity, 0), Demand::plus);
        }

        Demand peak() {
            Demand current = new Demand(preassignedRooms.size(), 0);
            Demand busiest = current;
            // Deltas at exactly the same time are combined, so a departure and the
            // next arrival use one slot. Booked and held peaks must be simultaneous.
            for (Demand delta : changes.values()) {
                current = current.plus(delta);
                if (current.total() > busiest.total()) busiest = current;
            }
            return busiest;
        }
    }
}
