package com.hotel.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Payload gửi lên khi tạo mới / cập nhật RoomType.
 * Dùng chung cho POST và PUT.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomTypeRequest {

    @NotBlank(message = "Tên loại phòng không được để trống")
    @Size(max = 100, message = "Tên loại phòng tối đa 100 ký tự")
    private String typeName;

    @Size(max = 100, message = "Tên loại phòng tiếng Anh tối đa 100 ký tự")
    private String typeNameEn;

    private String description;
    private String descriptionEn;

    @NotNull(message = "Giá không được để trống")
    @DecimalMin(value = "0.0", inclusive = false, message = "Giá phải lớn hơn 0")
    @Digits(integer = 10, fraction = 2, message = "Giá tối đa 10 chữ số nguyên, 2 chữ số thập phân")
    private BigDecimal price;

    @NotNull(message = "Sức chứa không được để trống")
    @Min(value = 1, message = "Sức chứa phải ít nhất 1 khách")
    @Max(value = 20, message = "Sức chứa tối đa 20 khách/phòng")
    private Integer maxGuests;

    @Size(max = 500, message = "URL ảnh tối đa 500 ký tự")
    private String imageUrl;

    @Size(max = 3, message = "Mỗi loại phòng có tối đa 3 ảnh")
    private List<
            @NotBlank(message = "URL ảnh không được để trống")
            @Size(max = 500, message = "URL ảnh tối đa 500 ký tự")
            String> imageUrls;

    /**
     * Tập ID các tiện nghi cần gán.
     * Nullable — không bắt buộc phải có facilities khi tạo room type.
     */
    private Set<Long> facilityIds;
}
