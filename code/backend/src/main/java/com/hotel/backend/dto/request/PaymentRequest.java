package com.hotel.backend.dto.request;
import lombok.Data;
import jakarta.validation.constraints.NotNull;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;


@Data
public class PaymentRequest {
 
    @NotNull(message = "bookingId không được để trống")
    private Long bookingId;
 
 
    private PaymentProvider provider;

    private PaymentPurpose purpose;

    /**
     * Chỉ giữ để đọc request từ client cũ. SePay VietQR dùng tài khoản ngân hàng
     * do server cấu hình và không tin bankCode do trình duyệt gửi lên.
     */
    private String bankCode;
 
    private String orderInfo; // Nội dung thanh toán (tuỳ chọn)
}
