package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    private String question;

    @Size(max = 100)
    private String conversationId;

}
