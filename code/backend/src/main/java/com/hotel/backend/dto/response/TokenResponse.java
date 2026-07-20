package com.hotel.backend.dto.response;

import java.io.Serializable;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonInclude;

@Getter@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenResponse implements Serializable{
    private String accessToken;
    private String refreshToken;
}
