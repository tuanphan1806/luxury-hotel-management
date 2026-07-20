package com.hotel.backend.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CashRefundCompleteRequest {
    @AssertTrue(message = "Phải xác nhận đã giao tiền mặt cho khách")
    private boolean confirmed;

    @PastOrPresent(message = "Thời điểm hoàn tiền không được nằm trong tương lai")
    private LocalDateTime refundedAt;
}
