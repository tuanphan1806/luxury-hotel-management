package com.hotel.backend.dto.response;

import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentRefund;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Thông tin hoàn tiền an toàn để khách theo dõi, không chứa dữ liệu ngân hàng nhạy cảm. */
@Data
@Builder
public class ReservationRefundResponse {
    private RefundChannel channel;
    private RefundStatus status;
    private Long amount;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime transferredAt;
    private String proofImageUrl;

    public static ReservationRefundResponse from(PaymentRefund refund) {
        return ReservationRefundResponse.builder()
                .channel(refund.getChannel())
                .status(refund.getStatus())
                .amount(refund.getAmount())
                .requestedAt(refund.getRequestedAt())
                .completedAt(refund.getCompletedAt())
                .transferredAt(refund.getManualTransferredAt())
                .proofImageUrl(refund.getProofAsset() != null ? refund.getProofAsset().getUrl() : null)
                .build();
    }
}
