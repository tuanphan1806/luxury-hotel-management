package com.hotel.backend.exception;

/**
 * Ném ra khi không tìm thấy entity trong DB.
 * Được GlobalExceptionHandler bắt và trả về HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, Long id) {
        super(String.format("%s không tìm thấy với id: %d", resourceName, id));
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
