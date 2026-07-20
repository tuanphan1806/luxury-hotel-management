package com.hotel.backend.service;

import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.util.VNPayUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VNPayRefundGatewayTest {

    private static final String SECRET = "refund-test-secret";

    @Mock VNPayConfig config;
    @Mock VNPayApiClient apiClient;

    private VNPayRefundGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new VNPayRefundGateway(config, apiClient);
        when(config.getApiUrl()).thenReturn("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        when(config.getTmnCode()).thenReturn("TESTCODE");
        when(config.getHashSecret()).thenReturn(SECRET);
        lenient().when(config.getVersion()).thenReturn("2.1.0");
        lenient().when(config.getRefundIpAddress()).thenReturn("203.0.113.10");
    }

    @Test
    void refundPostsMandatoryFieldsAndExactPipeChecksum() {
        PaymentRefund refund = refund();
        when(apiClient.postJson(eq(config.getApiUrl()), anyMap()))
                .thenReturn(signedRefundResponse("00", "00"));

        VNPayProviderResult result = gateway.refund(refund);

        assertEquals("00", result.responseCode());
        assertEquals("00", result.transactionStatus());
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(apiClient).postJson(eq(config.getApiUrl()), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("20260715090000", payload.get("vnp_TransactionDate"));
        assertEquals(5_000_000L, payload.get("vnp_Amount"));

        String expectedSignData = String.join("|",
                String.valueOf(payload.get("vnp_RequestId")), "2.1.0", "refund", "TESTCODE", "03",
                "PAY-REF-1", "5000000", "123456789", "20260715090000",
                "staff01", String.valueOf(payload.get("vnp_CreateDate")), "203.0.113.10",
                "Hoan tien do huy phong");
        assertEquals(VNPayUtil.hmacSHA512(SECRET, expectedSignData), payload.get("vnp_SecureHash"));
    }

    @Test
    void refundRejectsResponseWithInvalidChecksum() {
        Map<String, String> response = signedRefundResponse("00", "00");
        response.put("vnp_SecureHash", "invalid");
        when(apiClient.postJson(eq(config.getApiUrl()), anyMap())).thenReturn(response);

        assertThrows(RuntimeException.class, () -> gateway.refund(refund()));
    }

    @Test
    void refundRejectsLoopbackServerIpBeforeCallingVNPay() {
        when(config.getRefundIpAddress()).thenReturn("127.0.0.1");

        assertThrows(RuntimeException.class, () -> gateway.refund(refund()));
        verifyNoInteractions(apiClient);
    }

    @Test
    void refundRejectsOriginalPaymentWithoutProviderTransactionNumber() {
        PaymentRefund refund = refund();
        refund.getPaymentTransaction().setProviderTxnId(null);

        assertThrows(RuntimeException.class, () -> gateway.refund(refund));
        verifyNoInteractions(apiClient);
    }

    private PaymentRefund refund() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .provider(PaymentProvider.VNPAY)
                .txnRef("PAY-REF-1")
                .providerTxnId("123456789")
                .amount(100_000L)
                .build();
        return PaymentRefund.builder()
                .paymentTransaction(payment)
                .provider(PaymentProvider.VNPAY)
                .status(RefundStatus.REQUESTED)
                .amount(50_000L)
                .requestId("RF260715090000ABCDEF123456")
                .transactionType("03")
                .originalTransactionDate("20260715090000")
                .requestedBy("staff01")
                .reason("Hoàn tiền do hủy phòng")
                .build();
    }

    private Map<String, String> signedRefundResponse(String responseCode, String transactionStatus) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("vnp_ResponseId", "RESP-1");
        response.put("vnp_Command", "refund");
        response.put("vnp_ResponseCode", responseCode);
        response.put("vnp_Message", "Success");
        response.put("vnp_TmnCode", "TESTCODE");
        response.put("vnp_TxnRef", "PAY-REF-1");
        response.put("vnp_Amount", "5000000");
        response.put("vnp_BankCode", "NCB");
        response.put("vnp_PayDate", "20260715100000");
        response.put("vnp_TransactionNo", "987654321");
        response.put("vnp_TransactionType", "03");
        response.put("vnp_TransactionStatus", transactionStatus);
        response.put("vnp_OrderInfo", "Hoan tien do huy phong");
        String signData = String.join("|",
                response.get("vnp_ResponseId"), response.get("vnp_Command"), responseCode,
                response.get("vnp_Message"), response.get("vnp_TmnCode"), response.get("vnp_TxnRef"),
                response.get("vnp_Amount"), response.get("vnp_BankCode"), response.get("vnp_PayDate"),
                response.get("vnp_TransactionNo"), response.get("vnp_TransactionType"),
                transactionStatus, response.get("vnp_OrderInfo"));
        response.put("vnp_SecureHash", VNPayUtil.hmacSHA512(SECRET, signData));
        return response;
    }
}
