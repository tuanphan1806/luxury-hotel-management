package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OpenCashierShiftRequest {

    /**
     * Compatibility-only field. New clients do not send or display an opening
     * balance; the service always starts an operational shift at zero.
     */
    @Deprecated
    private BigDecimal openingCashAmount;

    @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    private String note;
}
