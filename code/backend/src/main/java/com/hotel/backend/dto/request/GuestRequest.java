package com.hotel.backend.dto.request;
import com.hotel.backend.constant.IdCardType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestRequest {
 
    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 100, message = "Họ tên không được quá 100 ký tự")
    private String fullName;
 
    @Size(max = 24, message = "Số điện thoại không được quá 24 ký tự")
    private String phone;
    @Email(message = "Email không đúng định dạng")
    @Size(max = 254, message = "Email không được quá 254 ký tự")
    private String email;
    @Size(max = 50, message = "Số giấy tờ không được quá 50 ký tự")
    private String idCardNumber;
    private IdCardType idCardType;
    private LocalDate dateOfBirth;
    private String nationality;
 
    @Builder.Default
    private Boolean isPrimary = false;
}
