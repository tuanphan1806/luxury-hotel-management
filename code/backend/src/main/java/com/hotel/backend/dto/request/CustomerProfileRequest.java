package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfileRequest {

    @Size(max = 150, message = "Họ tên không được quá 150 ký tự")
    private String fullName;

    @Size(max = 24, message = "Số điện thoại không được quá 24 ký tự")
    private String phone;

    @Email(message = "Email không đúng định dạng")
    @Size(max = 255, message = "Email không được quá 255 ký tự")
    private String email;

    @Size(max = 500, message = "Địa chỉ không được quá 500 ký tự")
    private String address;

    @Size(max = 50, message = "Số giấy tờ không được quá 50 ký tự")
    private String idCardNumber;
}
