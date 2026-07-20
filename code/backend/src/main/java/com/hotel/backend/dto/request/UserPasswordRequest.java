package com.hotel.backend.dto.request;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
@Getter
public class UserPasswordRequest implements Serializable{
    @NotNull(message = "id must be not null")
    private Long id;
    @NotBlank(message = "current password must be not null")
    private String currentPassword;
    @NotBlank(message = "password must be not null")
    @Size(min = 8, max = 72, message = "Mật khẩu mới phải có từ 8 đến 72 ký tự")
    private String password;
    @NotBlank(message = "confirm password must be not null")
    @Size(min = 8, max = 72, message = "Xác nhận mật khẩu phải có từ 8 đến 72 ký tự")
    private String confirmPassword;
}
