package com.hotel.backend.dto.response;

import java.io.Serializable;
import java.util.List;

import lombok.Getter;

import lombok.Setter;
@Getter
@Setter

public class UserPageResponse extends PageResponseAbstract implements Serializable{
    private List<UserResponse> users;
}
