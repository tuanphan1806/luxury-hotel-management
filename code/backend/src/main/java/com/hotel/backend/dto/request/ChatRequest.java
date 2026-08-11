package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChatRequest {

    @NotBlank
    @Size(max = 500)
    private String question;

    @Size(max = 100)
    private String conversationId;

    @Pattern(regexp = "(?i)vi|en")
    private String locale = "vi";

    @Valid
    @Size(max = 12)
    private List<ChatTurnRequest> history = new ArrayList<>();

    @Size(max = 1500)
    private String bookingContext;

    @Valid
    private ChatBookingStateRequest bookingState;

}
