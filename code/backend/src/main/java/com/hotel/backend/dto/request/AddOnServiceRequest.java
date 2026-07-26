package com.hotel.backend.dto.request;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.AddOnServiceCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnServiceRequest {

    @NotBlank(message = "Mã dịch vụ không được để trống")
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,64}$",
            message = "Mã dịch vụ chỉ gồm chữ, số, gạch dưới hoặc gạch ngang")
    private String code;

    @NotBlank(message = "Tên dịch vụ không được để trống")
    @Size(max = 255, message = "Tên dịch vụ tối đa 255 ký tự")
    private String name;

    @Size(max = 255, message = "Tên dịch vụ tiếng Anh tối đa 255 ký tự")
    private String nameEn;

    @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
    private String description;

    @Size(max = 2000, message = "Mô tả tiếng Anh tối đa 2000 ký tự")
    private String descriptionEn;

    @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
    private String imageUrl;

    @NotNull(message = "Nhóm dịch vụ không được để trống")
    private AddOnServiceCategory category;

    @NotNull(message = "Giá dịch vụ không được để trống")
    @DecimalMin(value = "0", message = "Giá dịch vụ không được âm")
    @Digits(integer = 10, fraction = 2,
            message = "Giá dịch vụ tối đa 10 chữ số nguyên và 2 chữ số thập phân")
    private BigDecimal price;

    @NotNull(message = "Đơn vị tính không được để trống")
    private AddOnPricingUnit pricingUnit;

    private Boolean bookingEnabled;
    private Boolean inStayEnabled;

    @Max(value = 100000, message = "Thứ tự hiển thị không hợp lệ")
    private Integer sortOrder;
}
