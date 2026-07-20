package com.hotel.backend.dto.request;

import lombok.Getter;

import java.io.Serializable;

import com.hotel.backend.constant.UserType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
@Getter
public class UserCreationWithTypeRequest implements Serializable{
    @NotBlank
    private String fullName;

    @NotBlank
    private String username;

    @Email(message = "email invalid")
    @NotBlank
    private String email;

    private UserType type;

    @NotBlank
    private String phone;

    private String address;
    private String imageUrl;
    @NotBlank
    @Size(min = 8, max = 72, message = "Mật khẩu phải có từ 8 đến 72 ký tự")
    private String password;
}
