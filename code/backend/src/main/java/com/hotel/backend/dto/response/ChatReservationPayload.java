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
public final class ChatReservationPayload implements ChatActionPayload {

    private LocalDateTime checkIn;

    private LocalDateTime checkOut;

    private Integer guestCount;

    private Integer adults;

    private Integer children;

    private String note;

    /** Bounded, sanitized booking conversation used only to refine the pending request. */
    private String context;

    private List<RoomTypeItemRequest> roomTypes;

    /** Room types selected in chat but still waiting for an explicit quantity. */
    private List<Long> pendingRoomTypeIds;
}
