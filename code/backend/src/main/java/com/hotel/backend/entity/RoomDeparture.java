package com.hotel.backend.entity;

import com.hotel.backend.constant.RoomBillingMode;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Embeddable
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class RoomDeparture {
    @Column(name = "departed_at", table = "reservation_room_departures")
    private LocalDateTime departedAt;
    @Column(name = "departure_room_name", table = "reservation_room_departures")
    private String roomName;
    @Column(name = "folio_number", table = "reservation_room_departures", length = 100)
    private String folioNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_mode", table = "reservation_room_departures")
    private RoomBillingMode billingMode;
    @Column(name = "departed_guests", table = "reservation_room_departures")
    private Integer guestCount;
    @Column(name = "billed_extra_guests", table = "reservation_room_departures")
    private Integer billedExtraGuests;
    @Column(name = "room_charge", table = "reservation_room_departures", precision = 14, scale = 2)
    private BigDecimal roomCharge;
    @Column(name = "included_late_fee", table = "reservation_room_departures", precision = 14, scale = 2)
    private BigDecimal includedLateFee;
    @Column(name = "extra_charge", table = "reservation_room_departures", precision = 14, scale = 2)
    private BigDecimal extraCharge;
    @Column(name = "service_charge", table = "reservation_room_departures", precision = 14, scale = 2)
    private BigDecimal serviceCharge;
    @Column(name = "additional_fee", table = "reservation_room_departures")
    private Long additionalFee;
    @Column(name = "allocated_payment", table = "reservation_room_departures", precision = 14, scale = 2)
    private BigDecimal allocatedPayment;
    @Column(name = "cash_collected", table = "reservation_room_departures")
    private Long cashCollected;
    @Column(name = "qr_collected", table = "reservation_room_departures")
    private Long qrCollected;
}
