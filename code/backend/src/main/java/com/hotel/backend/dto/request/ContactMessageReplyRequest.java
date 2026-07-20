package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContactMessageReplyRequest {
    @NotBlank(message = "Tiêu đề email không được để trống")
    @Size(max = 200, message = "Tiêu đề email tối đa 200 ký tự")
    private String subject;

    @NotBlank(message = "Nội dung phản hồi không được để trống")
    @Size(max = 5000, message = "Nội dung phản hồi tối đa 5000 ký tự")
    private String message;
}
