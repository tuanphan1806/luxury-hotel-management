package com.hotel.backend.constant;

/**
 * Trạng thái vòng đời của một tệp đã được upload.
 */
public enum MediaAssetStatus {
    /** Tệp đã lưu nhưng chưa được gắn vào bản ghi nghiệp vụ. */
    TEMPORARY,
    /** Tệp đang được một bản ghi nghiệp vụ sử dụng. */
    ACTIVE,
    /** Tệp đã bị thay thế/xóa và đang chờ dọn. */
    ORPHANED,
    /** Tệp vật lý đã được dọn; metadata được giữ lại làm tombstone. */
    DELETED
}
