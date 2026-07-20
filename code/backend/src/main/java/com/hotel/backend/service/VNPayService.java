package com.hotel.backend.service;

import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.util.VNPayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VNPayService {

    private final VNPayConfig vnPayConfig;
    private final PaymentTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final PaymentRefundService paymentRefundService;

    @PostConstruct
    void warnAboutMobileCallbackConfiguration() {
        if (isLocalUrl(vnPayConfig.getReturnUrl())) {
            log.warn("VNPay Return URL đang là localhost; QR quét trên điện thoại sẽ trả về localhost của điện thoại");
        }
    }
    // ==================== TẠO URL THANH TOÁN ====================

    /**
     * Tạo URL redirect sang trang thanh toán VNPay
     */
    public String createPaymentUrl(PaymentTransaction transaction, String ipAddress) {
        return createPaymentUrl(transaction, ipAddress, null);
    }

    public String createPaymentUrl(PaymentTransaction transaction, String ipAddress, String bankCode) {
        validatePaymentConfig();
        validateBankCode(bankCode);
        String txnRef = transaction.getTxnRef();
        long amount = transaction.getAmount();
        String orderInfo = transaction.getOrderInfo() != null
                ? transaction.getOrderInfo()
                : "Thanh toan dat phong " + transaction.getReservation().getId();
        String clientIp = isBlank(ipAddress) ? "127.0.0.1" : ipAddress;

        // Thời gian tạo và hết hạn giao dịch QR (5 phút)
        LocalDateTime vietnamNow = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String createDate = vietnamNow.format(formatter);
        String expireDate = vietnamNow.plusMinutes(5).format(formatter);

        // Tham số gửi sang VNPay (phải sort theo alphabet)
        Map<String, String> vnpParams = new TreeMap<>();
        vnpParams.put("vnp_Version", vnPayConfig.getVersion());
        vnpParams.put("vnp_Command", vnPayConfig.getCommand());
        vnpParams.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        vnpParams.put("vnp_Amount", String.valueOf(amount * 100)); // VNPay nhân 100
        vnpParams.put("vnp_CurrCode", vnPayConfig.getCurrCode());
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", orderInfo);
        vnpParams.put("vnp_OrderType", vnPayConfig.getOrderType());
        vnpParams.put("vnp_Locale", vnPayConfig.getLocale());
        vnpParams.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        vnpParams.put("vnp_IpAddr", clientIp);
        vnpParams.put("vnp_CreateDate", createDate);
        vnpParams.put("vnp_ExpireDate", expireDate);
        if (!isBlank(bankCode)) {
            vnpParams.put("vnp_BankCode", bankCode.trim());
        }

        transaction.setRequestedBankCode(isBlank(bankCode) ? null : bankCode.trim());
        transaction.setProviderCreateDate(createDate);

        // Tạo chuỗi hash
        String queryString = vnpParams.entrySet().stream()
                .filter(e -> !isBlank(e.getValue()))
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));

        String secureHash = VNPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), queryString);
        String paymentUrl = vnPayConfig.getPaymentUrl() + "?" + queryString + "&vnp_SecureHash=" + secureHash;

        log.info("Tạo URL thanh toán VNPay cho txnRef={}, amount={}", txnRef, amount);
        return paymentUrl;
    }

    // ==================== XỬ LÝ CALLBACK (Return URL) ====================

    /**
     * Xử lý khi VNPay redirect khách hàng về sau thanh toán
     * Return URL: khách hàng thấy trang kết quả
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentTransaction handleReturn(Map<String, String> params) {
        log.info("VNPay Return callback: txnRef={}", params.get("vnp_TxnRef"));

        // Xác thực chữ ký
        if (!verifySignature(params)) {
            log.error("VNPay Return: Chữ ký không hợp lệ");
            throw new RuntimeException("Chữ ký VNPay không hợp lệ");
        }

        String txnRef = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");
        String providerTxnId = params.get("vnp_TransactionNo");
        String bankCode = params.get("vnp_BankCode");

        PaymentTransaction transaction = lockCallbackTransaction(txnRef);

        long returnedAmount = Long.parseLong(params.getOrDefault("vnp_Amount", "0")) / 100;
        if (returnedAmount != transaction.getAmount()) {
            throw new RuntimeException("Số tiền VNPay trả về không khớp giao dịch");
        }

        // A signed success callback can arrive after the local timeout already
        // moved this transaction to FAILED. The provider captured real money,
        // therefore the event must be queued for a full refund, never ignored.
        if ("00".equals(responseCode) && transaction.getStatus() == PaymentStatus.FAILED) {
            transaction.setResponseCode(responseCode);
            transaction.setProviderTxnId(providerTxnId);
            applyProviderCallbackFields(transaction, params);
            markLatePaymentForRefund(transaction);
            return transactionRepository.save(transaction);
        }

        if (transaction.getStatus() != PaymentStatus.PENDING) {
            log.info("VNPay Return: giao dịch đã được xử lý trước đó, txnRef={}, status={}",
                    txnRef, transaction.getStatus());
            return transaction;
        }

        // Cập nhật trạng thái
        transaction.setResponseCode(responseCode);
        transaction.setProviderTxnId(providerTxnId);
        applyProviderCallbackFields(transaction, params);

        if ("00".equals(responseCode)) {
            if (!canAcceptSuccessfulPayment(transaction)) {
                markLatePaymentForRefund(transaction);
                return transactionRepository.save(transaction);
            }
            transaction.setStatus(PaymentStatus.SUCCESS);
            transaction.setPaidAt(LocalDateTime.now());
            transaction.setMessage("Thanh toán thành công");
            log.info("Thanh toán VNPay thành công: txnRef={}", txnRef);
            reservationService.convertHoldsAfterPayment(transaction.getReservation().getId());
        } else {
            transaction.setStatus(PaymentStatus.FAILED);
            transaction.setMessage(getVNPayMessage(responseCode));
            log.warn("Thanh toán VNPay thất bại: txnRef={}, code={}", txnRef, responseCode);
        }

        return transactionRepository.save(transaction);
    }

    // ==================== XỬ LÝ IPN (Server-to-Server) ====================

    /**
     * Xử lý IPN từ VNPay server gọi về (không qua browser)
     * Đây là nơi cập nhật DB chính xác nhất
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> handleIPN(Map<String, String> params) {
        log.info("VNPay IPN callback: txnRef={}", params.get("vnp_TxnRef"));

        Map<String, String> result = new HashMap<>();

        // Bước 1: Xác thực chữ ký
        if (!verifySignature(params)) {
            log.error("VNPay IPN: Chữ ký không hợp lệ");
            result.put("RspCode", "97");
            result.put("Message", "Invalid Checksum");
            return result;
        }

        String txnRef = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");
        String providerTxnId = params.get("vnp_TransactionNo");
        long vnpAmount = Long.parseLong(params.get("vnp_Amount")) / 100; // VNPay gửi * 100

        // Bước 2: Tìm giao dịch
        PaymentTransaction transaction = lockCallbackTransactionOrNull(txnRef);
        if (transaction == null) {
            log.error("VNPay IPN: Không tìm thấy giao dịch txnRef={}", txnRef);
            result.put("RspCode", "01");
            result.put("Message", "Order not found");
            return result;
        }

        // Bước 3: Kiểm tra số tiền
        if (vnpAmount != transaction.getAmount()) {
            log.error("VNPay IPN: Số tiền không khớp. Expected={}, Got={}", transaction.getAmount(), vnpAmount);
            result.put("RspCode", "04");
            result.put("Message", "Invalid Amount");
            return result;
        }

        // A successful signed IPN received after the local timeout represents
        // captured money. Record it for refund instead of returning RspCode 02.
        if ("00".equals(responseCode) && transaction.getStatus() == PaymentStatus.FAILED) {
            transaction.setResponseCode(responseCode);
            transaction.setProviderTxnId(providerTxnId);
            applyProviderCallbackFields(transaction, params);
            markLatePaymentForRefund(transaction);
            transactionRepository.save(transaction);
            result.put("RspCode", "00");
            result.put("Message", "Late successful payment recorded; refund pending");
            return result;
        }

        // Bước 4: Kiểm tra trạng thái (tránh xử lý 2 lần)
        if (transaction.getStatus() != PaymentStatus.PENDING) {
            log.warn("VNPay IPN: Giao dịch đã được xử lý rồi. txnRef={}", txnRef);
            result.put("RspCode", "02");
            result.put("Message", "Order already confirmed");
            return result;
        }

        // Bước 5: Cập nhật DB
        transaction.setResponseCode(responseCode);
        transaction.setProviderTxnId(providerTxnId);
        applyProviderCallbackFields(transaction, params);

        if ("00".equals(responseCode)) {
            if (!canAcceptSuccessfulPayment(transaction)) {
                markLatePaymentForRefund(transaction);
                transactionRepository.save(transaction);
                result.put("RspCode", "00");
                result.put("Message", "Payment received after reservation expiry; refund pending");
                return result;
            }
            transaction.setStatus(PaymentStatus.SUCCESS);
            transaction.setPaidAt(LocalDateTime.now());
            transaction.setMessage("Thanh toán thành công");

            reservationService.convertHoldsAfterPayment(transaction.getReservation().getId());
        } else {
            transaction.setStatus(PaymentStatus.FAILED);
            transaction.setMessage(getVNPayMessage(responseCode));
        }

        transactionRepository.save(transaction);
        log.info("VNPay IPN xử lý xong: txnRef={}, status={}", txnRef, transaction.getStatus());

        result.put("RspCode", "00");
        result.put("Message", "Confirm Success");
        return result;
    }

    private boolean canAcceptSuccessfulPayment(PaymentTransaction transaction) {
        ReservationStatus status = transaction.getReservation().getStatus();
        PaymentPurpose purpose = transaction.getPurpose();
        if (purpose == PaymentPurpose.DEPOSIT) {
            return status == ReservationStatus.PAYMENT_PENDING;
        }
        return status == ReservationStatus.CHECKED_IN;
    }

    /**
     * Use one lock order across callbacks, payment creation and hold expiry:
     * reservation first, payment transaction second. The initial unlocked lookup
     * only discovers the aggregate id; all state checks happen after both locks.
     */
    private PaymentTransaction lockCallbackTransaction(String txnRef) {
        PaymentTransaction transaction = lockCallbackTransactionOrNull(txnRef);
        if (transaction == null) {
            throw new RuntimeException("Không tìm thấy giao dịch: " + txnRef);
        }
        return transaction;
    }

    private PaymentTransaction lockCallbackTransactionOrNull(String txnRef) {
        Long reservationId = transactionRepository.findReservationIdByTxnRef(txnRef).orElse(null);
        if (reservationId == null) {
            return null;
        }
        reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        return transactionRepository.findByTxnRefForUpdate(txnRef).orElse(null);
    }

    private void markLatePaymentForRefund(PaymentTransaction transaction) {
        transaction.setStatus(PaymentStatus.REFUND_PENDING);
        transaction.setRefundAmount(transaction.getAmount());
        transaction.setRefundProvider(com.hotel.backend.constant.PaymentProvider.VNPAY);
        transaction.setPaidAt(LocalDateTime.now());
        transaction.setMessage("Thanh toán đến sau khi reservation hết hiệu lực; cần hoàn toàn bộ");
        paymentRefundService.requestLateCapturedPaymentRefund(transaction, "vnpay_callback");
        log.warn("Late VNPay payment moved to REFUND_PENDING: txnRef={}, reservationId={}",
                transaction.getTxnRef(), transaction.getReservation().getId());
    }

    // ==================== PRIVATE HELPERS ====================

    private void applyProviderCallbackFields(PaymentTransaction transaction, Map<String, String> params) {
        transaction.setBankCode(params.get("vnp_BankCode"));
        transaction.setCardType(params.get("vnp_CardType"));
        transaction.setProviderPayDate(params.get("vnp_PayDate"));
    }

    /**
     * Xác thực chữ ký từ VNPay
     */
    private boolean verifySignature(Map<String, String> params) {
        if (isBlank(vnPayConfig.getHashSecret())) {
            log.error("VNPay verify failed: missing vnpay.hash-secret");
            return false;
        }
        String receivedHash = params.get("vnp_SecureHash");
        if (receivedHash == null) return false;

        // Loại bỏ các field hash trước khi tính lại
        Map<String, String> signParams = new TreeMap<>(params);
        signParams.remove("vnp_SecureHash");
        signParams.remove("vnp_SecureHashType");

        String queryString = signParams.entrySet().stream()
                .filter(e -> !isBlank(e.getValue()))
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));

        String computedHash = VNPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), queryString);
        boolean valid = computedHash.equalsIgnoreCase(receivedHash);

        if (!valid) {
            log.error("Chữ ký không khớp. Computed={}, Received={}", computedHash, receivedHash);
        }
        return valid;
    }

    /**
     * Chuyển mã lỗi VNPay sang thông báo tiếng Việt
     */
    private String getVNPayMessage(String responseCode) {
        return switch (responseCode) {
            case "00" -> "Giao dịch thành công";
            case "07" -> "Trừ tiền thành công. Giao dịch bị nghi ngờ (liên quan tới lừa đảo, giao dịch bất thường)";
            case "09" -> "Thẻ/Tài khoản chưa đăng ký dịch vụ InternetBanking";
            case "10" -> "Xác thực thông tin thẻ/tài khoản không đúng quá 3 lần";
            case "11" -> "Đã hết hạn chờ thanh toán. Vui lòng thực hiện lại giao dịch";
            case "12" -> "Thẻ/Tài khoản bị khóa";
            case "13" -> "Sai mật khẩu xác thực giao dịch (OTP)";
            case "24" -> "Khách hàng hủy giao dịch";
            case "51" -> "Tài khoản không đủ số dư";
            case "65" -> "Tài khoản đã vượt quá hạn mức giao dịch trong ngày";
            case "75" -> "Ngân hàng thanh toán đang bảo trì";
            case "79" -> "Sai mật khẩu thanh toán quá số lần quy định";
            default -> "Giao dịch thất bại. Mã lỗi: " + responseCode;
        };
    }

    private void validatePaymentConfig() {
        List<String> missingFields = new ArrayList<>();
        if (isBlank(vnPayConfig.getPaymentUrl())) missingFields.add("vnpay.payment-url");
        if (isBlank(vnPayConfig.getReturnUrl())) missingFields.add("vnpay.return-url");
        if (isBlank(vnPayConfig.getTmnCode())) missingFields.add("vnpay.tmn-code");
        if (isBlank(vnPayConfig.getHashSecret())) missingFields.add("vnpay.hash-secret");
        if (isBlank(vnPayConfig.getVersion())) missingFields.add("vnpay.version");
        if (isBlank(vnPayConfig.getCommand())) missingFields.add("vnpay.command");
        if (isBlank(vnPayConfig.getCurrCode())) missingFields.add("vnpay.curr-code");
        if (isBlank(vnPayConfig.getOrderType())) missingFields.add("vnpay.order-type");
        if (isBlank(vnPayConfig.getLocale())) missingFields.add("vnpay.locale");

        if (!missingFields.isEmpty()) {
            String message = "Thiếu cấu hình VNPay: " + String.join(", ", missingFields);
            log.error(message);
            throw new AppException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    private void validateBankCode(String bankCode) {
        if (!isBlank(bankCode) && !Set.of("VNPAYQR", "VNBANK", "INTCARD").contains(bankCode.trim())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "bankCode chỉ nhận VNPAYQR, VNBANK hoặc INTCARD");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isLocalUrl(String value) {
        if (isBlank(value)) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("localhost") || normalized.contains("127.0.0.1")
                || normalized.contains("[::1]");
    }
}
