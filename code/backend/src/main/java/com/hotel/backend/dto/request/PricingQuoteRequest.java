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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteRequest {

    @NotNull(message = "Ngày check-in không được để trống")
    private LocalDateTime checkIn;

    @NotNull(message = "Ngày check-out không được để trống")
    private LocalDateTime checkOut;

    @NotNull(message = "Tổng số khách không được để trống")
    @Min(value = 1, message = "Tổng số khách phải ít nhất 1")
    @Max(value = 1000, message = "Tổng số khách vượt giới hạn cho phép")
    private Integer guestCount;

    @NotEmpty(message = "Phải chọn ít nhất 1 loại phòng")
    @Size(max = 100, message = "Một báo giá tối đa 100 dòng hạng phòng")
    private List<@NotNull(message = "Hạng phòng không được để trống")
            @Valid PricingQuoteRoomRequest> rooms;

    @Builder.Default
    @Size(max = 100, message = "Một báo giá tối đa 100 dịch vụ")
    private List<@NotNull(message = "Dịch vụ không được để trống")
            @Valid ServiceOrderRequest> services = List.of();
}
