package com.hotel.backend.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payload chuẩn trả về khi có lỗi.
 *
 * Format:
 * {
 *   "success"   : false,
 *   "status"    : 404,
 *   "error"     : "Not Found",
 *   "message"   : "RoomType không tìm thấy với id: 99",
 *   "errors"    : [ "typeName: không được để trống", ... ],   ← chỉ có khi validation lỗi
 *   "path"      : "/api/v1/room-types/99",
 *   "timestamp" : "2025-01-01 12:00:00"
 * }
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Builder.Default
    private boolean success = false;
    private int status;
    private String error;
    private String message;

    /** Danh sách lỗi validation từng field — chỉ xuất hiện khi có @Valid lỗi. */
    private List<String> errors;

    private String path;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
