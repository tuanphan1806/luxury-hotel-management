package com.hotel.backend.dto.response;

import com.hotel.backend.dto.request.RoomTypeItemRequest;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatReservationPayload {

    private LocalDateTime checkIn;

    private LocalDateTime checkOut;

    private Integer guestCount;

    private String note;

    private List<RoomTypeItemRequest> roomTypes;
}

