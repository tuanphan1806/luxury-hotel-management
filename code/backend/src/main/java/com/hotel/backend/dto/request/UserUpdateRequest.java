package com.hotel.backend.dto.request;

import java.io.Serializable;

import com.hotel.backend.constant.UserType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
