package com.hotel.backend.service;

import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.util.VNPayUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VNPayRefundGateway {

    private static final DateTimeFormatter VNP_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VNPayConfig config;
    private final VNPayApiClient apiClient;

    public String newRequestId() {
        String timestamp = LocalDateTime.now(VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        return "RF" + timestamp + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public VNPayProviderResult refund(PaymentRefund refund) {
        validateRefundRequest(refund);
        PaymentTransaction payment = refund.getPaymentTransaction();

        String createDate = LocalDateTime.now(VIETNAM_ZONE).format(VNP_DATE);
        String transactionNo = value(payment.getProviderTxnId());
        String orderInfo = ascii(refund.getReason(), "Hoan tien dat phong");
        String createBy = ascii(refund.getRequestedBy(), "hotel_system");
        String ipAddress = valueOr(config.getRefundIpAddress(), "127.0.0.1");
        long amount = Math.multiplyExact(refund.getAmount(), 100L);

        String signData = String.join("|",
                refund.getRequestId(), config.getVersion(), "refund", config.getTmnCode(),
                refund.getTransactionType(), payment.getTxnRef(), String.valueOf(amount),
                transactionNo, refund.getOriginalTransactionDate(), createBy, createDate,
                ipAddress, orderInfo);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vnp_RequestId", refund.getRequestId());
        payload.put("vnp_Version", config.getVersion());
        payload.put("vnp_Command", "refund");
        payload.put("vnp_TmnCode", config.getTmnCode());
        payload.put("vnp_TransactionType", refund.getTransactionType());
        payload.put("vnp_TxnRef", payment.getTxnRef());
        payload.put("vnp_Amount", amount);
        payload.put("vnp_OrderInfo", orderInfo);
        payload.put("vnp_TransactionNo", transactionNo);
        payload.put("vnp_TransactionDate", refund.getOriginalTransactionDate());
        payload.put("vnp_CreateBy", createBy);
        payload.put("vnp_CreateDate", createDate);
        payload.put("vnp_IpAddr", ipAddress);
        payload.put("vnp_SecureHash", VNPayUtil.hmacSHA512(config.getHashSecret(), signData));

        Map<String, String> response = apiClient.postJson(config.getApiUrl(), payload);
        verifyRefundResponse(response);
        validateResponseIdentity(response, payment.getTxnRef(), String.valueOf(amount));
        return providerResult(response);
    }

    public VNPayProviderResult query(PaymentRefund refund) {
        validateRefundRequest(refund);
        PaymentTransaction payment = refund.getPaymentTransaction();

        String requestId = newRequestId();
        String createDate = LocalDateTime.now(VIETNAM_ZONE).format(VNP_DATE);
        String orderInfo = "Truy van hoan tien " + refund.getId();
        String ipAddress = valueOr(config.getRefundIpAddress(), "127.0.0.1");
        String signData = String.join("|", requestId, config.getVersion(), "querydr",
                config.getTmnCode(), payment.getTxnRef(), refund.getOriginalTransactionDate(),
                createDate, ipAddress, orderInfo);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vnp_RequestId", requestId);
        payload.put("vnp_Version", config.getVersion());
        payload.put("vnp_Command", "querydr");
        payload.put("vnp_TmnCode", config.getTmnCode());
        payload.put("vnp_TxnRef", payment.getTxnRef());
        payload.put("vnp_OrderInfo", orderInfo);
        payload.put("vnp_TransactionNo", value(payment.getProviderTxnId()));
        payload.put("vnp_TransactionDate", refund.getOriginalTransactionDate());
        payload.put("vnp_CreateDate", createDate);
        payload.put("vnp_IpAddr", ipAddress);
        payload.put("vnp_SecureHash", VNPayUtil.hmacSHA512(config.getHashSecret(), signData));

        Map<String, String> response = apiClient.postJson(config.getApiUrl(), payload);
        verifyQueryResponse(response);
        validateResponseIdentity(response, payment.getTxnRef(), null);
        return providerResult(response);
    }

    /** Kiểm tra mọi điều kiện có thể xác định trước khi gửi request ra VNPay. */
    public void validateRefundRequest(PaymentRefund refund) {
        validateConfig();
        boolean legacyVnPay = refund != null && refund.getChannel() == null
                && refund.getProvider() == com.hotel.backend.constant.PaymentProvider.VNPAY;
        if (refund == null || (!legacyVnPay && refund.getChannel() != RefundChannel.VNPAY_ORIGINAL)
                || refund.getAmount() == null || refund.getAmount() <= 0L
                || isBlank(refund.getRequestId()) || refund.getRequestId().length() > 32
                || !List.of("02", "03").contains(refund.getTransactionType())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu refund thiếu amount, requestId hoặc transactionType hợp lệ");
        }
        validateOriginalPayment(refund.getPaymentTransaction(), refund.getOriginalTransactionDate());
        Math.multiplyExact(refund.getAmount(), 100L);
    }

    private void verifyRefundResponse(Map<String, String> response) {
        String signData = String.join("|",
                value(response.get("vnp_ResponseId")), value(response.get("vnp_Command")),
                value(response.get("vnp_ResponseCode")), value(response.get("vnp_Message")),
                value(response.get("vnp_TmnCode")), value(response.get("vnp_TxnRef")),
                value(response.get("vnp_Amount")), value(response.get("vnp_BankCode")),
                value(response.get("vnp_PayDate")), value(response.get("vnp_TransactionNo")),
                value(response.get("vnp_TransactionType")), value(response.get("vnp_TransactionStatus")),
                value(response.get("vnp_OrderInfo")));
        verifyResponseHash(response, signData);
    }

    private void verifyQueryResponse(Map<String, String> response) {
        String signData = String.join("|",
                value(response.get("vnp_ResponseId")), value(response.get("vnp_Command")),
                value(response.get("vnp_ResponseCode")), value(response.get("vnp_Message")),
                value(response.get("vnp_TmnCode")), value(response.get("vnp_TxnRef")),
                value(response.get("vnp_Amount")), value(response.get("vnp_BankCode")),
                value(response.get("vnp_PayDate")), value(response.get("vnp_TransactionNo")),
                value(response.get("vnp_TransactionType")), value(response.get("vnp_TransactionStatus")),
                value(response.get("vnp_OrderInfo")), value(response.get("vnp_PromotionCode")),
                value(response.get("vnp_PromotionAmount")));
        verifyResponseHash(response, signData);
    }

    private void verifyResponseHash(Map<String, String> response, String signData) {
        String received = response.get("vnp_SecureHash");
        String expected = VNPayUtil.hmacSHA512(config.getHashSecret(), signData);
        if (received == null || !expected.equalsIgnoreCase(received)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Checksum response VNPay không hợp lệ");
        }
    }

    private void validateResponseIdentity(Map<String, String> response, String txnRef, String amount) {
        if (!config.getTmnCode().equals(response.get("vnp_TmnCode"))
                || !txnRef.equals(response.get("vnp_TxnRef"))
                || (amount != null && !amount.equals(response.get("vnp_Amount")))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Response VNPay không khớp merchant, txnRef hoặc số tiền refund");
        }
    }

    private VNPayProviderResult providerResult(Map<String, String> response) {
        return new VNPayProviderResult(
                response.get("vnp_ResponseCode"), response.get("vnp_TransactionStatus"),
                response.get("vnp_TransactionType"), response.get("vnp_TransactionNo"),
                response.get("vnp_Message"));
    }

    private void validateConfig() {
        if (isBlank(config.getApiUrl()) || isBlank(config.getTmnCode()) || isBlank(config.getHashSecret())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thiếu vnpay.api-url, vnpay.tmn-code hoặc vnpay.hash-secret cho refund");
        }
        String serverIp = config.getRefundIpAddress();
        if (isBlank(serverIp)
                || serverIp.equals("127.0.0.1")
                || serverIp.equals("0.0.0.0")
                || serverIp.equals("::1")
                || serverIp.equalsIgnoreCase("localhost")) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "VNPAY_REFUND_IP_ADDRESS phải là IP máy chủ gọi API, không được dùng localhost");
        }
        try {
            URI endpoint = URI.create(config.getApiUrl());
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || isBlank(endpoint.getHost())) {
                throw new IllegalArgumentException("VNPay API URL phải là HTTPS hợp lệ");
            }
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "vnpay.api-url không phải URL HTTPS hợp lệ");
        }
    }

    private void validateOriginalPayment(PaymentTransaction payment, String transactionDate) {
        if (payment == null || payment.getProvider() != com.hotel.backend.constant.PaymentProvider.VNPAY) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ giao dịch thu tiền gốc qua VNPay mới có thể hoàn qua VNPay");
        }
        if (isBlank(payment.getTxnRef())
                || isBlank(payment.getProviderTxnId())
                || isBlank(transactionDate)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Giao dịch thiếu txnRef, vnp_TransactionNo hoặc vnp_CreateDate gốc để hoàn tiền");
        }
    }

    private String ascii(String input, String fallback) {
        String source = isBlank(input) ? fallback : input;
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9 ._-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.substring(0, Math.min(normalized.length(), 245));
    }

    private String value(String value) {
        return value != null ? value : "";
    }

    private String valueOr(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
