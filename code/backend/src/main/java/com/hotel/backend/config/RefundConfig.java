package com.hotel.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.refund")
public class RefundConfig {

    /** Khóa AES-256 dạng Base64, chỉ tồn tại ở backend. */
    private String dataEncryptionKey;
    private PayoutMode payoutMode = PayoutMode.MANUAL;
    private MerchantBank merchantBank = new MerchantBank();

    public enum PayoutMode {
        MANUAL
    }

    @Getter
    @Setter
    public static class MerchantBank {
        private String bankCode;
        private String bankName;
        private String accountNumber;
        private String accountHolder;
    }
}
