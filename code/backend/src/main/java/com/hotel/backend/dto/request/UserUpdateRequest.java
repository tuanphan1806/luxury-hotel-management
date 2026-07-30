package com.hotel.backend.dto.request;

import java.io.Serializable;

import com.hotel.backend.constant.UserType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
@Getter
@Setter
@ToString
public class UserUpdateRequest implements Serializable{

    // private Long id;

    @NotBlank
    private String fullName;
    @NotBlank
    @Size(min = 3, max = 30, message = "Tên đăng nhập phải có từ 3 đến 30 ký tự")
    @Pattern(
            regexp = "^[A-Za-z0-9._-]+$",
            message = "Tên đăng nhập chỉ được chứa chữ cái, chữ số, dấu chấm, gạch dưới hoặc gạch ngang")
    private String username;
    @Email(message = "email invalid")
    @NotBlank
    private String email;

    private UserType type;    
    // @NotBlank
    // private String password;

    @NotBlank
    private String phone;

    private String address;
    private String imageUrl;
}
