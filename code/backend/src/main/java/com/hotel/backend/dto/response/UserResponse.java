package com.hotel.backend.dto.response;

import java.io.Serializable;

import com.hotel.backend.constant.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserResponse implements Serializable{
    private Long id;
    private String fullName;
    private String username;
    private String email;
    private String phone;
    private String address;
    private UserType type ;
    private UserStatus status ;
    private String imageUrl;
}

