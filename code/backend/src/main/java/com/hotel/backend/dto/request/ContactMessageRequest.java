package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContactMessageRequest {

    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
    private String name;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    @Size(max = 255, message = "Email tối đa 255 ký tự")
    private String email;

    @Size(max = 30, message = "Số điện thoại tối đa 30 ký tự")
    private String phone;

    @NotBlank(message = "Chủ đề không được để trống")
    @Size(max = 200, message = "Chủ đề tối đa 200 ký tự")
    private String subject;

    @NotBlank(message = "Nội dung không được để trống")
    @Size(max = 5000, message = "Nội dung tối đa 5000 ký tự")
    private String message;
}
