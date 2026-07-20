package com.hotel.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sepay")
public class SePayConfig {
    /** Tài khoản hiển thị trong VietQR; có thể là VA của ngân hàng. */
    private String qrBankAccount;
    /**
     * Tài khoản thanh toán gốc SePay trả về trong accountNumber.
     * Với QR dùng VA, giá trị này vẫn là tài khoản chính nhận quyết toán.
     */
    private String merchantBankAccount;
    /** BIN/code/short name VietQR, ví dụ 970436 hoặc VCB. */
    private String qrBankCode;
    private String qrBankName;
    private String qrAccountHolder;
    private String qrTemplate = "compact";
    private String qrBaseUrl = "https://vietqr.app/img";
    private String storeName = "Luxury Hotel";

    /** SePay API v2 chỉ dùng server-side để đối soát khi webhook bị chậm/mất. */
    private String apiBaseUrl = "https://userapi.sepay.vn/v2";
    private String apiAccessToken;
    /** UUID tài khoản ngân hàng trên SePay; dùng để giới hạn dữ liệu đối soát. */
    private String apiBankAccountId;
    private boolean reconciliationEnabled = true;
    private long reconciliationIntervalMs = 30_000L;
    private int reconciliationLookbackHours = 24;
    private int reconciliationOverlapMinutes = 5;
    private int reconciliationMaxPages = 10;

    /** API key alternative; SePay sends it in Authorization: Apikey ... */
    private String webhookApiKey;
    /** Recommended HMAC-SHA256 secret for raw-body signature verification. */
    private String webhookSecret;
    private long webhookTimestampToleranceSeconds = 300L;
    /** Giới hạn request webhook theo địa chỉ peer đã được resolver xác thực. */
    private int webhookRateLimitPerMinute = 120;
    private int paymentTimeoutMinutes = 5;
    /** Thời gian chờ webhook giao dịch tiền ra trước khi cho phép fallback. */
    private int refundWebhookTimeoutMinutes = 45;
}
