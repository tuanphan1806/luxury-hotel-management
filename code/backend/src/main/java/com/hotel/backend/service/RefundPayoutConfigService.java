package com.hotel.backend.service;

import com.hotel.backend.config.RefundConfig;
import com.hotel.backend.dto.response.RefundPayoutConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefundPayoutConfigService {

    private final RefundConfig config;

    public RefundPayoutConfigResponse getMasked() {
        RefundConfig.MerchantBank bank = config.getMerchantBank();
        boolean configured = hasText(bank.getBankCode())
                && hasText(bank.getBankName())
                && hasText(bank.getAccountNumber())
                && hasText(bank.getAccountHolder());
        return RefundPayoutConfigResponse.builder()
                .payoutMode(config.getPayoutMode())
                .configured(configured)
                .bankCode(trim(bank.getBankCode()))
                .bankName(trim(bank.getBankName()))
                .accountName(RefundDataCipher.maskPersonName(bank.getAccountHolder()))
                .accountNumberMasked(RefundDataCipher.maskAccountNumber(bank.getAccountNumber()))
                .automaticTransferEnabled(false)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
