package com.hotel.backend.constant;

/**
 * Loại bản ghi nghiệp vụ đang sở hữu một media asset.
 */
public enum MediaAssetOwnerType {
    USER_AVATAR(UploadFolder.AVATAR),
    FACILITY(UploadFolder.FACILITIES),
    GALLERY(UploadFolder.GALLERY),
    ROOM_TYPE(UploadFolder.ROOM_TYPES),
    ROOM(UploadFolder.ROOMS);

    private final UploadFolder requiredPurpose;

    MediaAssetOwnerType(UploadFolder requiredPurpose) {
        this.requiredPurpose = requiredPurpose;
    }

    public UploadFolder getRequiredPurpose() {
        return requiredPurpose;
    }
}
