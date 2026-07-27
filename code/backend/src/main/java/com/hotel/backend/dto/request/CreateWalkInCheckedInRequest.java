package com.hotel.backend.dto.request;

import com.hotel.backend.constant.WalkInPaymentOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWalkInCheckedInRequest {

    private Long customerProfileId;

    @Valid
    private CustomerProfileRequest customer;

    @NotNull(message = "Ngày check-out không được để trống")
    private LocalDateTime checkOut;

    @NotNull(message = "Số khách không được để trống")
    @Min(value = 1, message = "Số khách phải ít nhất 1 người")
    @Max(value = 1000, message = "Số khách vượt giới hạn cho phép")
    private Integer guestCount;

    @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
    private String note;

    @NotEmpty(message = "Phải chọn ít nhất 1 phòng")
    @Size(max = 100, message = "Một đơn tối đa 100 phòng")
    @Valid
    private List<AssignRoomRequest> rooms;

    /**
     * Optional, explicit overrides for walk-in only. The amount is the total
     * stay price per physical room, not the room type's base hourly price.
     */
    @Valid
    @Size(max = 100, message = "Một đơn tối đa 100 mức giá thay thế")
    private List<WalkInPriceOverrideRequest> priceOverrides;

    @Valid
    @Size(max = 100, message = "Một đơn tối đa 100 dịch vụ")
    @Builder.Default
    private List<ServiceOrderRequest> services = List.of();

    @NotNull(message = "Phương án thanh toán walk-in không được để trống")
    private WalkInPaymentOption paymentOption;

    @Positive(message = "Số tiền thanh toán phải lớn hơn 0")
    private Long paymentAmount;
}
