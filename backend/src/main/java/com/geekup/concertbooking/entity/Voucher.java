package com.geekup.concertbooking.entity;

import com.geekup.concertbooking.shared.enums.DiscountType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "vouchers",
    indexes = {
        @Index(name = "idx_voucher_code", columnList = "code", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;  // FLASHSALE10, VIP20...

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private DiscountType discountType;

    /**
     * discountType = PERCENT → giá trị từ 1–100
     * discountType = FIXED   → số tiền VND giảm trực tiếp
     */
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    /**
     * Tổng số lần voucher này có thể dùng (trên toàn hệ thống).
     * Null = unlimited.
     */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    @Builder.Default
    private Integer usedCount = 0;

    /**
     * Đơn tối thiểu để áp dụng voucher.
     * Null = không có yêu cầu tối thiểu.
     */
    @Column(name = "min_order_amount", precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDateTime validTo;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Helper: kiểm tra voucher còn hợp lệ không
    public boolean isValid(LocalDateTime now, BigDecimal orderAmount) {
        if (!isActive) return false;
        if (now.isBefore(validFrom) || now.isAfter(validTo)) return false;
        if (maxUses != null && usedCount >= maxUses) return false;
        if (minOrderAmount != null && orderAmount.compareTo(minOrderAmount) < 0) return false;
        return true;
    }
}
