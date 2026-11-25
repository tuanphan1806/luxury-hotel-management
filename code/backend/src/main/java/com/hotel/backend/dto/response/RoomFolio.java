package com.hotel.backend.dto.response;

import com.hotel.backend.constant.RoomBillingMode;
import com.hotel.backend.entity.ReservationRoom;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public record RoomFolio(Long slotId, String number, String roomName, LocalDateTime departedAt,
                        RoomBillingMode billingMode, BigDecimal roomCharge, BigDecimal extraCharge,
                        BigDecimal serviceCharge, long additionalFee, BigDecimal allocatedPayment, long cashCollected,
                        List<ServiceLine> services, List<RoomLine> rooms, BigDecimal includedLateFee, long qrCollected) {
    public record ServiceLine(Long id, String name, int quantity, BigDecimal amount) {}
    public record RoomLine(Long slotId, String name, BigDecimal roomCharge, BigDecimal extraCharge) {}

    public static List<RoomFolio> issued(Collection<ReservationRoom> slots) {
        var byNumber = slots.stream().filter(s -> s.getDeparture() != null)
                .sorted(Comparator.comparing((ReservationRoom s) -> s.getDeparture().getDepartedAt()).thenComparing(ReservationRoom::getId))
                .collect(Collectors.groupingBy(s -> s.getDeparture().getFolioNumber(), LinkedHashMap::new, Collectors.toList()));
        return byNumber.values().stream().map(RoomFolio::from).toList();
    }

    public static RoomFolio from(List<ReservationRoom> slots) {
        var first = slots.get(0);
        var firstDeparture = first.getDeparture();
        List<RoomLine> rooms = new ArrayList<>();
        List<ServiceLine> services = new ArrayList<>();
        BigDecimal room = BigDecimal.ZERO, extra = BigDecimal.ZERO, service = BigDecimal.ZERO, paid = BigDecimal.ZERO, late = BigDecimal.ZERO;
        long cash = 0, qr = 0, fee = 0;
        for (var slot : slots) {
            var d = slot.getDeparture();
            rooms.add(new RoomLine(slot.getId(), d.getRoomName(), d.getRoomCharge(), d.getExtraCharge()));
            slot.getDepartureServices().forEach(s -> services.add(new ServiceLine(s.getOrderId(), s.getName(), s.getQuantity(), s.getAmount())));
            room = room.add(d.getRoomCharge());
            extra = extra.add(d.getExtraCharge());
            service = service.add(d.getServiceCharge());
            late = late.add(d.getIncludedLateFee());
            paid = paid.add(d.getAllocatedPayment());
            cash += d.getCashCollected();
            qr += d.getQrCollected() == null ? 0 : d.getQrCollected();
            fee += d.getAdditionalFee();
        }
        return new RoomFolio(first.getId(), firstDeparture.getFolioNumber(), rooms.stream().map(RoomLine::name).collect(Collectors.joining(", ")),
                firstDeparture.getDepartedAt(), firstDeparture.getBillingMode(), room, extra, service, fee, paid, cash, services, rooms, late, qr);
    }
}
