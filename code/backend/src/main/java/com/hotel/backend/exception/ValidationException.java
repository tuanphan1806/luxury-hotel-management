package com.hotel.backend.exception;

/**
 * Ném ra khi dữ liệu nghiệp vụ không hợp lệ (ngoài validation annotation).
 * Ví dụ: minPrice > maxPrice khi lọc theo khoảng giá.
 * Được GlobalExceptionHandler bắt và trả về HTTP 400 Bad Request.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}