package com.hotel.backend.dto.request;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RefundRequest {

    @NotBlank(message = "transactionId không được để trống")
    private String transactionId; // ID giao dịch nội bộ cần hoàn tiền

    @NotNull(message = "amount không được để trống")
    @Min(value = 1000, message = "Số tiền hoàn tối thiểu là 1,000 VND")
    private Long amount; // Số tiền hoàn toàn bộ của giao dịch

    private RefundChannel refundChannel;

    /** @deprecated Backend tự định tuyến theo giao dịch thu tiền gốc. */
    @Deprecated
    private PaymentProvider provider;

    private String reason; // Lý do hoàn tiền
}
