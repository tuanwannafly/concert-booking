package com.geekup.concertbooking.module.booking.dto;

import com.geekup.concertbooking.entity.BookingItem;

import java.math.BigDecimal;

/**
 * Thông tin 1 dòng vé trong response booking.
 * subtotal = unitPrice × quantity (tính tại thời điểm booking, snapshot giá).
 */
public record BookingItemResponse(

    Long ticketCategoryId,
    String categoryName,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal

) {
    /**
     * Factory method từ entity BookingItem.
     */
    public static BookingItemResponse from(BookingItem item) {
        return new BookingItemResponse(
            item.getTicketCategory().getId(),
            item.getTicketCategory().getName(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
