package com.hotel.backend.service;

import java.io.IOException;

/** Safe diagnostics only; never retain provider bodies or recipient/token data. */
public class EmailDeliveryException extends IOException {
    public enum Kind { RETRYABLE, PERMANENT, AMBIGUOUS }
    private final Kind kind;
    public EmailDeliveryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
