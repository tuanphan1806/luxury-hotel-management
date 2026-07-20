package com.hotel.backend.service;

import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.util.VNPayUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class VNPayServiceTest {

    private static final String HASH_SECRET = "test-vnpay-secret";

    @Mock VNPayConfig vnPayConfig;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationService reservationService;
    @Mock PaymentRefundService paymentRefundService;

    private VNPayService vnPayService;

    @BeforeEach
    void setUp() {
        vnPayService = new VNPayService(
                vnPayConfig, transactionRepository, reservationRepository, reservationService,
                paymentRefundService);
        when(vnPayConfig.getHashSecret()).thenReturn(HASH_SECRET);
    }

    @Test
    void successfulIpnAfterLocalTimeoutMovesMoneyToRefundPending() {
        PaymentTransaction transaction = timedOutTransaction();
        stubCallbackLocks(transaction);

        Map<String, String> result = vnPayService.handleIPN(signedSuccessParams(transaction));

        assertEquals("00", result.get("RspCode"));
        assertEquals(PaymentStatus.REFUND_PENDING, transaction.getStatus());
        assertEquals(transaction.getAmount(), transaction.getRefundAmount());
        assertNotNull(transaction.getPaidAt());
        assertEquals("VNP-LATE-001", transaction.getProviderTxnId());
        org.mockito.Mockito.verify(paymentRefundService)
                .requestLateCapturedPaymentRefund(transaction, "vnpay_callback");

        var locks = inOrder(transactionRepository, reservationRepository);
        locks.verify(transactionRepository).findReservationIdByTxnRef(transaction.getTxnRef());
        locks.verify(reservationRepository).findByIdForUpdate(transaction.getReservation().getId());
        locks.verify(transactionRepository).findByTxnRefForUpdate(transaction.getTxnRef());
    }

    @Test
    void qrPaymentUrlContainsExplicitChannelAndVietnamTimestamp() {
        when(vnPayConfig.getPaymentUrl()).thenReturn("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        when(vnPayConfig.getReturnUrl()).thenReturn("https://example.test/api/payments/vnpay/return");
        when(vnPayConfig.getTmnCode()).thenReturn("TESTCODE");
        when(vnPayConfig.getVersion()).thenReturn("2.1.0");
        when(vnPayConfig.getCommand()).thenReturn("pay");
        when(vnPayConfig.getCurrCode()).thenReturn("VND");
        when(vnPayConfig.getOrderType()).thenReturn("hotel");
        when(vnPayConfig.getLocale()).thenReturn("vn");

        Reservation reservation = Reservation.builder().status(ReservationStatus.PAYMENT_PENDING).build();
        reservation.setId(77L);
        PaymentTransaction transaction = PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("QR-77")
                .amount(100_000L)
                .orderInfo("Thanh toan dat phong QR77")
                .build();

        String paymentUrl = vnPayService.createPaymentUrl(transaction, "127.0.0.1", "VNPAYQR");

        assertTrue(paymentUrl.contains("vnp_BankCode=VNPAYQR"));
        assertEquals("VNPAYQR", transaction.getRequestedBankCode());
        LocalDateTime signedTime = LocalDateTime.parse(
                transaction.getProviderCreateDate(), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long seconds = Math.abs(java.time.Duration.between(
                signedTime, LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))).toSeconds());
        assertTrue(seconds < 10, "vnp_CreateDate must use GMT+7");
    }

    @Test
    void repeatedLateSuccessCallbackDoesNotCreateAnotherRefund() {
        PaymentTransaction transaction = timedOutTransaction();
        stubCallbackLocks(transaction);
        Map<String, String> params = signedSuccessParams(transaction);

        assertEquals("00", vnPayService.handleIPN(params).get("RspCode"));
        Long firstRefundAmount = transaction.getRefundAmount();
        assertEquals("02", vnPayService.handleIPN(params).get("RspCode"));

        assertEquals(PaymentStatus.REFUND_PENDING, transaction.getStatus());
        assertEquals(firstRefundAmount, transaction.getRefundAmount());
    }

    @Test
    void successfulCallbackAfterExpiryCancellationIsRecordedForRefund() {
        PaymentTransaction transaction = timedOutTransaction();
        transaction.setStatus(PaymentStatus.PENDING);
        stubCallbackLocks(transaction);

        Map<String, String> result = vnPayService.handleIPN(signedSuccessParams(transaction));

        assertEquals("00", result.get("RspCode"));
        assertEquals(PaymentStatus.REFUND_PENDING, transaction.getStatus());
        assertEquals(transaction.getAmount(), transaction.getRefundAmount());
    }

    private void stubCallbackLocks(PaymentTransaction transaction) {
        when(transactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.findReservationIdByTxnRef(transaction.getTxnRef()))
                .thenReturn(Optional.of(transaction.getReservation().getId()));
        when(reservationRepository.findByIdForUpdate(transaction.getReservation().getId()))
                .thenReturn(Optional.of(transaction.getReservation()));
        when(transactionRepository.findByTxnRefForUpdate(transaction.getTxnRef()))
                .thenReturn(Optional.of(transaction));
    }

    private PaymentTransaction timedOutTransaction() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-LATE-91")
                .status(ReservationStatus.CANCELLED)
                .build();
        reservation.setId(91L);
        return PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("LATE-TEST-91")
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.FAILED)
                .amount(50_000L)
                .currency("VND")
                .message("Hết hạn chờ thanh toán")
                .build();
    }

    private Map<String, String> signedSuccessParams(PaymentTransaction transaction) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", String.valueOf(transaction.getAmount() * 100));
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "VNP-LATE-001");
        params.put("vnp_TxnRef", transaction.getTxnRef());
        String query = params.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(HASH_SECRET, query));
        return params;
    }
}
