package com.hotel.backend.dto.request;

import com.hotel.backend.constant.CheckoutCorrectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CheckoutReconciliationResolutionRequest {
    private boolean approve;

    private CheckoutCorrectionType correctionType;

    /** Required only for FEE_CORRECTION. */
    @PositiveOrZero(message = "Phụ phí mới không được âm")
    private Long correctedAdditionalFee;

    /** Required only for LINK_EXISTING_PAYMENT. */
    private String paymentProviderEventId;
    private String paymentTransactionId;

    @NotBlank(message = "Mã lý do xử lý không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Ghi chú xử lý không được để trống")
    @Size(max = 1000, message = "Ghi chú xử lý không được quá 1000 ký tự")
    private String note;

    private Long evidenceAssetId;
}
