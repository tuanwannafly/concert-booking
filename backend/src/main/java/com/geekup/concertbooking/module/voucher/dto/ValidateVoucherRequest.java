package com.geekup.concertbooking.module.voucher.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body để validate voucher trước khi tạo booking.
 * Customer gửi lên để preview số tiền giảm — không ghi DB.
 */
public record ValidateVoucherRequest(

    @NotBlank(message = "Mã voucher không được để trống")
    String voucherCode,

    @NotNull(message = "Giá trị đơn hàng không được null")
    @DecimalMin(value = "0", message = "Giá trị đơn hàng phải >= 0")
    BigDecimal orderAmount
) {}
