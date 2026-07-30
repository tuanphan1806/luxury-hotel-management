package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CloseCashierShiftRequest {

    /**
     * Compatibility-only field. Closing a shift no longer asks operators to
     * re-enter a total already calculated from immutable transactions.
     */
    @Deprecated
    private BigDecimal countedCashAmount;

    @Size(max = 1000, message = "Ghi chú đóng ca tối đa 1000 ký tự")
    private String note;

    @Deprecated
    @Size(max = 500, message = "Lý do chênh lệch tối đa 500 ký tự")
    private String varianceReason;
}
