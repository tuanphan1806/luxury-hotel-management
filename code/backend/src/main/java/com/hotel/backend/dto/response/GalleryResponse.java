package com.hotel.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Payload trả về cho client sau mọi thao tác với Gallery.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GalleryResponse {

    private Long id;
    private String title;
    private String titleEn;
    private String type;
    private String imageUrl;

    /**
     * Chỉ trả về roomId thay vì toàn bộ Room object — tránh dữ liệu thừa.
     * Client tự lookup Room nếu cần thêm thông tin.
     */

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
