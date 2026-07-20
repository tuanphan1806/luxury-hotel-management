package com.hotel.backend.exception;

/**
 * Ném ra khi cố tạo/cập nhật dữ liệu bị trùng unique field.
 * Được GlobalExceptionHandler bắt và trả về HTTP 409 Conflict.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s đã tồn tại với %s: '%s'", resourceName, fieldName, fieldValue));
    }
}