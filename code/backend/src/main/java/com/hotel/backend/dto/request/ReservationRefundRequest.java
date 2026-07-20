package com.hotel.backend.dto.request;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import lombok.Data;

@Data
public class ReservationRefundRequest {
    private RefundChannel refundChannel;

    /** @deprecated Backend tự định tuyến theo từng giao dịch thu tiền gốc. */
    @Deprecated
    private PaymentProvider refundProvider;
}
