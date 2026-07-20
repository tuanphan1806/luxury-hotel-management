package com.hotel.backend.dto.request;
 
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancelReservationRequest {
 
    private String cancellationReason;
    /** Staff/Admin quyết định có hoàn toàn bộ số tiền đã thanh toán hay không. */
    @Builder.Default
    private Boolean refundPayment = false;

    /** Kênh staff chọn khi duyệt hoàn: tiền mặt tại quầy hoặc QR/chuyển khoản. */
    private RefundChannel refundChannel;

    /**
     * Phí phạt do Staff/Admin nhập. Backend luôn lấy số tiền khách sạn đang
     * thực giữ từ ledger và tự tính số tiền hoàn; client không được khai
     * refund amount.
     */
    @Min(value = 0, message = "Phí phạt hủy không được âm")
    private Long cancellationPenaltyAmount;

    @Size(max = 80, message = "Mã lý do phạt không được quá 80 ký tự")
    private String penaltyReasonCode;

    @Size(max = 500, message = "Ghi chú phí phạt không được quá 500 ký tự")
    private String penaltyNote;

    /** Khách gửi cùng yêu cầu hủy để sẵn sàng nhận hoàn qua QR/chuyển khoản. */
    @Valid
    private RefundRecipientRequest refundRecipient;

    /** Contract cũ; backend bỏ qua và tự định tuyến theo giao dịch gốc. */
    @Deprecated
    private PaymentProvider refundProvider;
}
