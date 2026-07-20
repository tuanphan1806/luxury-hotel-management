package com.hotel.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;

/**
 * Envelope chuẩn cho mọi API response.
 *
 * Format thống nhất:
 * {
 *   "success"   : true | false,
 *   "message"   : "...",          // null nếu không có message
 *   "data"      : { ... },        // null nếu không có payload (ví dụ: DELETE)
 *   "timestamp" : "2025-01-01 12:00:00"
 * }
 *
 * @param <T> kiểu dữ liệu của trường data
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // ── Static factory methods ────────────────────────────────────────────────

    /** Thành công, có data, không có message. */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    /** Thành công, có data và message. */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /** Thành công, chỉ có message (dùng cho DELETE). */
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    /** Lỗi, chỉ có message. */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(HttpStatus status, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message("Error " + status.value() + ": " + message)
                .build();
    }
}
