package com.hotel.backend.exception;

import com.hotel.backend.constant.OAuthLoginError;
import lombok.Getter;

@Getter
public class OAuthLoginException extends RuntimeException {

    private final OAuthLoginError error;

    public OAuthLoginException(OAuthLoginError error) {
        super(error.getCode());
        this.error = error;
    }
}
