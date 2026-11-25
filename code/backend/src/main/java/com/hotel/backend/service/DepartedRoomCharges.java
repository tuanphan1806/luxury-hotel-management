package com.hotel.backend.service;

import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.RoomDeparture;
import com.hotel.backend.pricing.PricingBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The part already priced at actual room departure is never repriced at the group's checkout. */
record DepartedRoomCharges(int totalRooms, int released, int guests, int extraGuests,
                          BigDecimal roomCharge, BigDecimal extraCharge, List<RoomDeparture> departures) {
    static DepartedRoomCharges from(ReservationRoomType line) {
        int released = 0, guests = 0, extras = 0;
        BigDecimal room = BigDecimal.ZERO, extra = BigDecimal.ZERO;
        List<RoomDeparture> departures = new ArrayList<>();
        for (var slot : line.getRooms()) {
            RoomDeparture departure = slot.getDeparture();
            if (departure == null) continue;
            departures.add(departure);
            released++;
            guests += departure.getGuestCount();
            extras += departure.getBilledExtraGuests();
            room = room.add(departure.getRoomCharge());
            extra = extra.add(departure.getExtraCharge());
        }
        departures.sort(Comparator.comparing(RoomDeparture::getDepartedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new DepartedRoomCharges(line.getQuantity(), released, guests, extras, room, extra, departures);
    }

    BigDecimal withActiveRooms(BigDecimal full) {
        if (released == 0) return full;
        return roomCharge.add(full.subtract(share(full, released, totalRooms)));
    }

    PricingBreakdown usage(PricingBreakdown allRooms, int totalGuests, int includedPerRoom, BigDecimal extraGuestRate) {
        if (released == 0) return allRooms;
        int activeRooms = totalRooms - released;
        int activeGuests = totalGuests - guests;
        int activeExtras = Math.max(0, activeGuests - activeRooms * includedPerRoom);
        BigDecimal room = withActiveRooms(allRooms.roomCharge());
        // Each package already started with a pooled allowance. Releasing a room affects the next cycle.
        // Each completed folio remains a lower bound for the charge already allocated to that room.
        long guestCycles = 0;
        int cycleGuests = totalGuests, cycleRooms = totalRooms, nextDeparture = 0;
        BigDecimal undatedCharges = BigDecimal.ZERO;
        for (var cycle : allRooms.cycles()) {
            while (nextDeparture < departures.size()) {
                var departure = departures.get(nextDeparture);
                LocalDateTime left = departure.getDepartedAt();
                if (left != null && left.isAfter(cycle.billableStart())) break;
                if (left == null) undatedCharges = undatedCharges.add(departure.getExtraCharge());
                cycleGuests -= departure.getGuestCount();
                cycleRooms--;
                nextDeparture++;
            }
            guestCycles += Math.max(0, cycleGuests - cycleRooms * includedPerRoom);
        }
        BigDecimal extra = released == totalRooms ? extraCharge : extraCharge.max(
                undatedCharges.add(extraGuestRate.multiply(BigDecimal.valueOf(guestCycles))));
        return new PricingBreakdown(allRooms.actualMinutes(), allRooms.stayClassification(), allRooms.appliedPackage(),
                allRooms.transitionReason(), allRooms.fullDays(), allRooms.remainderMinutes(), allRooms.packageCycles(),
                allRooms.chargedExtraUnits(), allRooms.packageIncludedCheckout(), allRooms.roomChargePerRoom(),
                room, extraGuests + activeExtras, extra, room.add(extra), allRooms.cycles());
    }

    BigDecimal withActiveExtras(BigDecimal full, int fullExtraGuests) {
        if (released == 0) return full;
        if (released == totalRooms) return extraCharge;
        return extraCharge.add(full.subtract(fullExtraGuests > 0
                ? share(full, Math.min(extraGuests, fullExtraGuests), fullExtraGuests)
                : share(full, released, totalRooms)));
    }

    static BigDecimal share(BigDecimal amount, int units, int total) {
        if (units == 0) return BigDecimal.ZERO;
        if (units == total) return amount;
        return amount.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(total), 0, RoundingMode.DOWN);
    }
}
