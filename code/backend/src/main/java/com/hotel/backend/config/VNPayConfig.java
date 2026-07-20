package com.hotel.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vnpay")
public class VNPayConfig {
    private String tmnCode;
    private String hashSecret;
    private String paymentUrl;
    private String returnUrl;
    private String apiUrl;
    /** IP public của máy chủ merchant dùng trong query/refund API. */
    private String refundIpAddress = "127.0.0.1";
    /** Cho phép worker gửi yêu cầu refund thật tới VNPay. */
    private boolean refundEnabled = false;
    private String version = "2.1.0";
    private String command = "pay";
    private String currCode = "VND";
    private String locale = "vn";
    private String orderType = "hotel";
}
