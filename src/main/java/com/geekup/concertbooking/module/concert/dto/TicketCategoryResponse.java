package com.geekup.concertbooking.module.concert.dto;

import com.geekup.concertbooking.entity.TicketCategory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class TicketCategoryResponse {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer totalQuantity;

    /**
     * Số vé khả dụng hiển thị cho client.
     * - Concert PUBLISHED: lấy từ Redis (real-time counter), fallback về DB nếu key chưa tồn tại.
     * - Concert DRAFT/ENDED: lấy từ DB.
     * Không expose trực tiếp field DB để tránh race condition khi đọc.
     */
    private Long availableQuantity;

    private Integer maxPerBooking;

    /**
     * Build từ entity + giá trị availableQty đã được resolve (từ Redis hoặc DB).
     */
    public static TicketCategoryResponse from(TicketCategory category, Long resolvedAvailableQty) {
        return TicketCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .price(category.getPrice())
                .totalQuantity(category.getTotalQuantity())
                .availableQuantity(resolvedAvailableQty)
                .maxPerBooking(category.getMaxPerBooking())
                .build();
    }

    /**
     * Convenience factory: lấy availableQuantity trực tiếp từ DB entity.
     * Dùng khi concert chưa PUBLISHED.
     */
    public static TicketCategoryResponse fromDb(TicketCategory category) {
        return from(category, category.getAvailableQuantity().longValue());
    }
}
