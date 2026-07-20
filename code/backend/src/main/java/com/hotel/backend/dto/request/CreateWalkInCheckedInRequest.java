package com.hotel.backend.dto.request;

import com.hotel.backend.constant.WalkInPaymentOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
    private Integer guestCount;

    private String note;

    @NotEmpty(message = "Phải chọn ít nhất 1 phòng")
    @Valid
    private List<AssignRoomRequest> rooms;

    /**
     * Optional, explicit overrides for walk-in only. The amount is the total
     * stay price per physical room, not the room type's base hourly price.
     */
    @Valid
    private List<WalkInPriceOverrideRequest> priceOverrides;

    @NotNull(message = "Phương án thanh toán walk-in không được để trống")
    private WalkInPaymentOption paymentOption;

    @Positive(message = "Số tiền thanh toán phải lớn hơn 0")
    private Long paymentAmount;
}
