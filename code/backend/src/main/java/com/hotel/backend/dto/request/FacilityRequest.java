package com.hotel.backend.dto.request;


import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Payload gửi lên khi tạo mới / cập nhật Facility.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacilityRequest {

    @NotBlank(message = "Tên tiện nghi không được để trống")
    @Size(min = 2, max = 255, message = "Tên tiện nghi phải từ 2 đến 255 ký tự")
    private String facilityName;

    @Size(max = 255, message = "Tên tiện nghi tiếng Anh tối đa 255 ký tự")
    private String facilityNameEn;

    @NotBlank(message = "Phân loại tiện nghi không được để trống")
    @Size(max = 255, message = "Loại tiện nghi tối đa 255 ký tự")
    private String type;

    @Size(max = 1000, message = "Mô tả tiếng Việt tối đa 1000 ký tự")
    private String description;
    @Size(max = 1000, message = "Mô tả tiếng Anh tối đa 1000 ký tự")
    private String descriptionEn;


    @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
    private String imageUrl;
}
