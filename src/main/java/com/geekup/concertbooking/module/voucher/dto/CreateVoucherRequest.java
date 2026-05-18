package com.geekup.concertbooking.module.voucher.dto;

import com.geekup.concertbooking.shared.enums.DiscountType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request body để Admin tạo voucher mới.
 * Voucher không thể chỉnh sửa sau khi tạo (xem assumptions.md).
 */
public record CreateVoucherRequest(

    @NotBlank(message = "Code voucher không được để trống")
    @Size(max = 50, message = "Code tối đa 50 ký tự")
    @Pattern(
        regexp = "^[A-Z0-9_]+$",
        message = "Code chỉ được chứa chữ hoa, số và dấu gạch dưới"
    )
    String code,

    @NotNull(message = "Loại giảm giá không được null")
    DiscountType discountType,

    @NotNull(message = "Giá trị giảm không được null")
    @DecimalMin(value = "0.01", message = "Giá trị giảm phải > 0")
    BigDecimal discountValue,

    /** Null = unlimited */
    @Min(value = 1, message = "maxUses phải >= 1 nếu có giới hạn")
    Integer maxUses,

    /** Null = không yêu cầu đơn tối thiểu */
    @DecimalMin(value = "0", message = "minOrderAmount phải >= 0")
    BigDecimal minOrderAmount,

    @NotNull(message = "validFrom không được null")
    LocalDateTime validFrom,

    @NotNull(message = "validTo không được null")
    LocalDateTime validTo
) {}
