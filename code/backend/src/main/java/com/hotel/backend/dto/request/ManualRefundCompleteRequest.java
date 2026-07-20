package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ManualRefundCompleteRequest {
    @NotBlank(message = "recipientId không được để trống")
    private String recipientId;

    @NotNull(message = "recipientVersion không được để trống")
    @PositiveOrZero(message = "recipientVersion không hợp lệ")
    private Long recipientVersion;

    @PastOrPresent(message = "Thời điểm chuyển khoản không được nằm trong tương lai")
    private LocalDateTime transferredAt;

    @Positive(message = "Minh chứng chuyển khoản không hợp lệ")
    private Long proofAssetId;

    @NotBlank(message = "Lý do xác nhận thủ công không được để trống")
    @Size(max = 255, message = "Lý do xác nhận thủ công tối đa 255 ký tự")
    private String fallbackReason;
}
