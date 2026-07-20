package com.hotel.backend.dto.response;

import com.hotel.backend.config.RefundConfig;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RefundPayoutConfigResponse {
    private RefundConfig.PayoutMode payoutMode;
    private boolean configured;
    private String bankCode;
    private String bankName;
    private String accountName;
    private String accountNumberMasked;
    private boolean automaticTransferEnabled;
}
