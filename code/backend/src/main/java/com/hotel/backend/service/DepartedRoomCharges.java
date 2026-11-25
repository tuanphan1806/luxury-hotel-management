package com.hotel.backend.service;

import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.RoomDeparture;
import com.hotel.backend.pricing.PricingBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The part already priced at actual room departure is never repriced at the group's checkout. */
record DepartedRoomCharges(int totalRooms, int released, int guests, int extraGuests,
                          BigDecimal roomCharge, BigDecimal extraCharge) {
    static DepartedRoomCharges from(ReservationRoomType line) {
        int released = 0, guests = 0, extras = 0;
        BigDecimal room = BigDecimal.ZERO, extra = BigDecimal.ZERO;
        for (var slot : line.getRooms()) {
            RoomDeparture departure = slot.getDeparture();
            if (departure == null) continue;
            released++;
            guests += departure.getGuestCount();
            extras += departure.getBilledExtraGuests();
            room = room.add(departure.getRoomCharge());
            extra = extra.add(departure.getExtraCharge());
        }
        return new DepartedRoomCharges(line.getQuantity(), released, guests, extras, room, extra);
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
        BigDecimal extra = extraCharge.add(extraGuestRate.multiply(BigDecimal.valueOf(activeExtras))
                .multiply(BigDecimal.valueOf(allRooms.packageCycles())));
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
