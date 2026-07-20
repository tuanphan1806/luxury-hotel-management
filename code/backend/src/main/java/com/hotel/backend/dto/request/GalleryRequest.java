package com.hotel.backend.dto.request;

import java.io.Serializable;

import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Payload gửi lên khi tạo mới / cập nhật Gallery.
 * room_id nullable — null nghĩa là ảnh chung của khách sạn, không gắn phòng cụ thể.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GalleryRequest implements Serializable{

    @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
    private String title;

    @Size(max = 255, message = "Tiêu đề tiếng Anh tối đa 255 ký tự")
    private String titleEn;

    @Size(max = 255, message = "Loại ảnh tối đa 255 ký tự")
    private String type;

    @NotBlank(message = "URL ảnh không được để trống")
    @Size(max = 500, message = "URL ảnh tối đa 500 ký tự")
    private String imageUrl;
}
