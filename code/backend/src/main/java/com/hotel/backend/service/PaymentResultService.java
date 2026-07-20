package com.hotel.backend.service;

import com.hotel.backend.dto.response.PublicPaymentResultResponse;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentResultService {

    private final PaymentTransactionRepository transactionRepository;
    private final SePayService sePayService;
    private final PaymentRefundService paymentRefundService;

    @Transactional(readOnly = true)
    public PublicPaymentResultResponse getPublicResult(String transactionId) {
        return transactionRepository.findById(transactionId)
                .map(transaction -> PublicPaymentResultResponse.from(
                        transaction,
                        transaction.getProvider() == com.hotel.backend.constant.PaymentProvider.SEPAY
                                ? sePayService.instructionsFor(transaction) : null,
                        paymentRefundService.getPaymentRefundSummary(transaction.getId())))
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kết quả giao dịch"));
    }
}
