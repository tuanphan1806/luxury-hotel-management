package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Email;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfileRequest {

    private String fullName;

    private String phone;

    @Email(message = "email invalid")
    private String email;

    private String address;

    private String idCardNumber;
}
