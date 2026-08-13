package com.hotel.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutExtraGuestChargeLineResponse {
    private Long reservationRoomTypeId;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private Integer roomQuantity;
    private Integer actualGuestCount;
    private Integer includedGuestsPerRoom;
    private Integer includedGuestCapacity;
    private Integer maxGuestsPerRoom;
    private Integer extraGuestCount;
    private Integer packageCycles;
    private Long extraGuestPricePerCycle;
    private Long amount;
}
