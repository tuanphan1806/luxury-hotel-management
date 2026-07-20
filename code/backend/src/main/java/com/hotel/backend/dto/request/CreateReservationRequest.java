package com.hotel.backend.dto.request;
 
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import com.hotel.backend.constant.PaymentPlan;

import java.time.LocalDateTime;
import java.util.List;
 
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
 
    @Min(value = 1, message = "Số khách phải ít nhất 1 người")
    private Integer guestCount;
 
    private String note;

    @Builder.Default
    private PaymentPlan paymentPlan = PaymentPlan.DEPOSIT_50;
 
    @NotEmpty(message = "Phải chọn ít nhất 1 loại phòng")
    @Valid
    private List<RoomTypeItemRequest> roomTypes;
}
