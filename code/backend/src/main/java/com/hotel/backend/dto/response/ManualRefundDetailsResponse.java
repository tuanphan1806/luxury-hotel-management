package com.hotel.backend.dto.response;

import com.hotel.backend.constant.RefundRecipientStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ManualRefundDetailsResponse {
    private String refundId;
    private Long reservationId;
    private String reservationCode;
    private Long amount;
    private Long expectedAmount;
    private String refundCode;
    private String status;
    private String canonicalStatus;
    private String recipientId;
    private Long recipientVersion;
    private RefundRecipientStatus recipientStatus;
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountHolderName;
    private String transferContent;
    private String refundQrCodeUrl;
    private Long proofAssetId;
    private String proofImageUrl;
    private String proofContentType;
    private boolean awaitingBankConfirmation;
    private Instant fallbackAvailableAtUtc;
    private boolean fallbackAvailable;
    private boolean fallbackOpened;
    private String fallbackOpenedBy;
    private String fallbackReason;
    private String completionMethod;
    private String bankReferenceCode;
}
