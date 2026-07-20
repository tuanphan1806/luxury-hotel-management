package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Administrative recovery request for an existing, durable SePay event.
 *
 * Financial facts deliberately are not accepted from the client. Amount,
 * provider timestamp, merchant account and provider identifiers are read from
 * the stored provider event by the service.
 */
@Data
public class ManualPaymentReconciliationRequest {
    @NotBlank(message = "paymentTransactionId không được để trống")
    private String paymentTransactionId;

    @NotBlank(message = "Mã lý do không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Ghi chú đối soát không được để trống")
    @Size(max = 500, message = "Ghi chú đối soát không được quá 500 ký tự")
    private String note;

    @Size(max = 255, message = "Tham chiếu minh chứng không được quá 255 ký tự")
    private String evidenceReference;
}
