package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefundRecipientRequest {

    @NotBlank(message = "Mã ngân hàng không được để trống")
    @Pattern(regexp = "^[A-Za-z0-9]{2,20}$", message = "Mã ngân hàng không hợp lệ")
    private String bankCode;

    @NotBlank(message = "Tên ngân hàng không được để trống")
    @Size(min = 2, max = 100, message = "Tên ngân hàng phải từ 2 đến 100 ký tự")
    private String bankName;

    @NotBlank(message = "Số tài khoản không được để trống")
    @Pattern(regexp = "^[0-9]{6,24}$", message = "Số tài khoản phải gồm 6 đến 24 chữ số")
    private String accountNumber;

    @NotBlank(message = "Tên chủ tài khoản không được để trống")
    @Size(min = 2, max = 100, message = "Tên chủ tài khoản phải từ 2 đến 100 ký tự")
    private String accountHolderName;
}
