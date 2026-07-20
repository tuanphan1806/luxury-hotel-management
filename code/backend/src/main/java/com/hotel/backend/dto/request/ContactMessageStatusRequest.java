package com.hotel.backend.dto.request;

import com.hotel.backend.constant.ContactMessageStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContactMessageStatusRequest {
    @NotNull(message = "Trạng thái không được để trống")
    private ContactMessageStatus status;
}
