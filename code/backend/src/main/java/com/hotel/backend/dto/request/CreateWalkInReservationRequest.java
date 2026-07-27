package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWalkInReservationRequest {

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

    @NotEmpty(message = "Phải chọn ít nhất 1 loại phòng")
    @Size(max = 100, message = "Một đơn tối đa 100 dòng hạng phòng")
    @Valid
    private List<RoomTypeItemRequest> roomTypes;

    @Valid
    @Size(max = 100, message = "Một đơn tối đa 100 dịch vụ")
    @Builder.Default
    private List<ServiceOrderRequest> services = List.of();
}
