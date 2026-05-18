package com.geekup.concertbooking.module.voucher.dto;

import com.geekup.concertbooking.shared.enums.DiscountType;

import java.math.BigDecimal;

/**
 * Response trả về sau khi validate voucher thành công.
 * Bao gồm số tiền giảm đã tính sẵn và tổng tiền cuối.
 *
 * Không phản ánh trạng thái đã apply — chỉ là preview.
 * BookingService.applyVoucher() mới thực sự ghi DB.
 */
public record ValidateVoucherResponse(

    /** Mã voucher đã validate */
    String voucherCode,

    /** Loại giảm giá */
    DiscountType discountType,

    /** Giá trị discount (% hoặc số tiền VND cố định) */
    BigDecimal discountValue,

    /** Số tiền thực tế được giảm (đã tính từ orderAmount) */
    BigDecimal discountAmount,

    /** Tổng tiền sau khi áp dụng voucher (orderAmount - discountAmount) */
    BigDecimal finalAmount
) {}
