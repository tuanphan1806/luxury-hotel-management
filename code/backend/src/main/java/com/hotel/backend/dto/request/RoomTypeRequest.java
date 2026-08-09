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

    @NotNull(message = "Số khách đã bao gồm trong giá không được để trống")
    @Min(value = 1, message = "Giá phòng phải bao gồm ít nhất 1 khách")
    @Max(value = 20, message = "Số khách đã bao gồm tối đa là 20")
    private Integer includedGuests;

    @NotNull(message = "Giá 2 giờ đầu không được để trống")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Giá 2 giờ đầu phải lớn hơn 0")
    @Digits(integer = 10, fraction = 0,
            message = "Giá 2 giờ đầu phải là số VND nguyên")
    private BigDecimal firstBlockPrice;

    @NotNull(message = "Giá mỗi giờ thêm không được để trống")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Giá mỗi giờ thêm phải lớn hơn 0")
    @Digits(integer = 10, fraction = 0,
            message = "Giá mỗi giờ thêm phải là số VND nguyên")
    private BigDecimal extraUnitPrice;

    @NotNull(message = "Giá qua đêm không được để trống")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Giá qua đêm phải lớn hơn 0")
    @Digits(integer = 10, fraction = 0,
            message = "Giá qua đêm phải là số VND nguyên")
    private BigDecimal overnightPrice;

    @NotNull(message = "Giá ngày đêm không được để trống")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Giá ngày đêm phải lớn hơn 0")
    @Digits(integer = 10, fraction = 0,
            message = "Giá ngày đêm phải là số VND nguyên")
    private BigDecimal dailyPrice;

    @NotNull(message = "Phụ thu khách thêm không được để trống")
    @DecimalMin(value = "0.0", message = "Phụ thu khách thêm không được âm")
    @Digits(integer = 10, fraction = 0,
            message = "Phụ thu khách thêm phải là số VND nguyên")
    private BigDecimal extraGuestPrice;

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
