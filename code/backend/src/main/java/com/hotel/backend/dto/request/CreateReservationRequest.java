package com.hotel.backend.dto.request;
 
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import com.hotel.backend.constant.PaymentPlan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateReservationRequest {

    @Valid
    private CustomerProfileRequest customer;
 
    @NotNull(message = "Ngày check-in không được để trống")
    private LocalDateTime checkIn;
 
    @NotNull(message = "Ngày check-out không được để trống")
    private LocalDateTime checkOut;
 
    @NotNull(message = "Số khách không được để trống")
    @Min(value = 1, message = "Số khách phải ít nhất 1 người")
    @Max(value = 1000, message = "Số khách vượt giới hạn cho phép")
    private Integer guestCount;
 
    @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
    private String note;

    @Builder.Default
    private PaymentPlan paymentPlan = PaymentPlan.DEPOSIT_50;
 
    @NotEmpty(message = "Phải chọn ít nhất 1 loại phòng")
    @Size(max = 100, message = "Một đơn tối đa 100 dòng hạng phòng")
    @Valid
    private List<RoomTypeItemRequest> roomTypes;

    @Valid
    @Size(max = 100, message = "Một đơn tối đa 100 dịch vụ")
    @Builder.Default
    private List<ServiceOrderRequest> services = List.of();

    /**
     * Optional for compatibility. New Pricing V2 clients send both fields;
     * legacy clients omit both and keep the existing LEGACY_V1 calculation.
     */
    private UUID quoteId;

    @Size(min = 64, max = 64, message = "Quote hash phải có đúng 64 ký tự")
    private String quoteHash;
}
