package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatTurnRequest {

    @NotBlank
    @Pattern(regexp = "user|assistant")
    private String role;

    @NotBlank
    @Size(max = 500)
    private String content;
}
