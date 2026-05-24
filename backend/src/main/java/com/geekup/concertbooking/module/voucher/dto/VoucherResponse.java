package com.geekup.concertbooking.module.voucher.dto;

import com.geekup.concertbooking.entity.Voucher;
import com.geekup.concertbooking.shared.enums.DiscountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Full voucher representation dùng cho Ops endpoints (ADMIN).
 * Bao gồm usedCount và status tính toán động.
 */
public record VoucherResponse(

    Long id,
    String code,
    DiscountType discountType,
    BigDecimal discountValue,
    Integer maxUses,
    Integer usedCount,
    BigDecimal minOrderAmount,
    LocalDateTime validFrom,
    LocalDateTime validTo,
    Boolean isActive,

    /**
     * Trạng thái tổng hợp:
     * - ACTIVE   : isActive=true và chưa hết hạn và còn lượt dùng
     * - EXPIRED  : validTo đã qua
     * - INACTIVE : isActive=false
     * - EXHAUSTED: đã hết lượt dùng (maxUses != null && usedCount >= maxUses)
     */
    String status
) {
    /**
     * Factory method — tính status tại thời điểm now.
     */
    public static VoucherResponse from(Voucher v) {
        return from(v, LocalDateTime.now());
    }

    public static VoucherResponse from(Voucher v, LocalDateTime now) {
        String status = computeStatus(v, now);
        return new VoucherResponse(
            v.getId(),
            v.getCode(),
            v.getDiscountType(),
            v.getDiscountValue(),
            v.getMaxUses(),
            v.getUsedCount(),
            v.getMinOrderAmount(),
            v.getValidFrom(),
            v.getValidTo(),
            v.getIsActive(),
            status
        );
    }

    private static String computeStatus(Voucher v, LocalDateTime now) {
        if (!v.getIsActive()) return "INACTIVE";
        if (now.isAfter(v.getValidTo())) return "EXPIRED";
        if (v.getMaxUses() != null && v.getUsedCount() >= v.getMaxUses()) return "EXHAUSTED";
        return "ACTIVE";
    }
}
