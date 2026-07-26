package com.hotel.backend.constant;

import lombok.Getter;

@Getter
public enum UploadFolder {
    AVATAR("avatar"),
    FACILITIES("facilities"),
    GALLERY("gallery"),
    ROOM_TYPES("room_types"),
    ROOMS("rooms"),
    ADD_ON_SERVICES("add_on_services"),
    REFUND_PROOFS("refund_proofs");

    private final String path;

    UploadFolder(String path) {
        this.path = path;
    }
}
