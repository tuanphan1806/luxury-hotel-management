package com.hotel.backend.dto.response;

import com.hotel.backend.constant.RefundRecipientMethod;
import com.hotel.backend.constant.RefundRecipientStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RefundRecipientResponse {
    private String recipientId;
    private String refundId;
    private Long reservationId;
    private RefundRecipientMethod method;
    private RefundRecipientStatus status;
    private String bankCode;
    private String bankName;
    private String accountNumberMasked;
    private String accountHolderNameMasked;
    private LocalDateTime providedAt;
    private boolean required;
}
