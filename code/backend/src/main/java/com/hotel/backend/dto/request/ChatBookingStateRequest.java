package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured state for a booking inquiry that is still being refined in chat.
 *
 * <p>The state is supplied by the public client, so every value is treated as
 * untrusted input and is revalidated against current availability before the
 * chatbot offers the canonical booking hand-off.</p>
 */
@Data
public class ChatBookingStateRequest {

    private LocalDateTime checkIn;

    private LocalDateTime checkOut;

    @Min(value = 1, message = "Số người lớn phải ít nhất 1")
    @Max(value = 1000, message = "Số người lớn vượt giới hạn cho phép")
    private Integer adults;

    @Min(value = 0, message = "Số trẻ em không được âm")
    @Max(value = 1000, message = "Số trẻ em vượt giới hạn cho phép")
    private Integer children;

    @Valid
    @Size(max = 12)
    private List<RoomTypeItemRequest> roomTypes = new ArrayList<>();

    /** Room types selected in chat but still waiting for an explicit quantity. */
    @Size(max = 12)
    private List<@Positive Long> pendingRoomTypeIds = new ArrayList<>();

    /**
     * Bounded compatibility context for fields that have not become
     * structured yet (for example, a room name before dates are supplied).
     */
    @Size(max = 1500)
    private String context;
}
