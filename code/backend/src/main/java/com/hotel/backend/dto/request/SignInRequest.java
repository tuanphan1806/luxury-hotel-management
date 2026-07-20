package com.hotel.backend.dto.request;

import java.io.Serializable;

import lombok.Getter;
import jakarta.validation.constraints.NotBlank;

@Getter
public class SignInRequest implements Serializable{
    @NotBlank(message = "Tên đăng nhập hoặc email không được để trống")
    private String username;
    @NotBlank(message = "Mật khẩu không được để trống")
    private String password;
}
