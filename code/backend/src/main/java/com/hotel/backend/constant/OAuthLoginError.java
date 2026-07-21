package com.hotel.backend.constant;

import lombok.Getter;

@Getter
public enum OAuthLoginError {
    ACCOUNT_CONFLICT("account_conflict"),
    MISSING_EMAIL("missing_email"),
    UNVERIFIED_EMAIL("unverified_email"),
    EMAIL_VERIFICATION_REQUIRED("email_verification_required"),
    ACCOUNT_DISABLED("account_disabled"),
    PROVIDER_ERROR("provider_error"),
    OAUTH_NOT_CONFIGURED("oauth_not_configured");

    private final String code;

    OAuthLoginError(String code) {
        this.code = code;
    }
}
