package com.hotel.backend.dto.request;
import com.hotel.backend.constant.IdCardType;

import jakarta.validation.constraints.NotBlank;
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
    private String fullName;
 
    private String phone;
    private String email;
    private String idCardNumber;
    private IdCardType idCardType;
    private LocalDate dateOfBirth;
    private String nationality;
 
    @Builder.Default
    private Boolean isPrimary = false;
}