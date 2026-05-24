package com.geekup.concertbooking.module.booking.dto;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.shared.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response đầy đủ cho 1 booking.
 * Được serialize vào Redis (idempotency cache) và trả về API.
 */
public record BookingResponse(

    Long id,
    Long concertId,
    String concertName,
    BookingStatus status,

    /** Tổng tiền trước khi áp voucher. */
    BigDecimal totalAmount,

    /** Số tiền thực tế phải thanh toán (sau discount). */
    BigDecimal finalAmount,

    /** Số tiền được giảm (null nếu không dùng voucher). */
    BigDecimal discountAmount,

    /** Code voucher đã dùng (null nếu không dùng). */
    String voucherCode,

    /** Thời điểm booking hết hạn nếu chưa thanh toán. */
    LocalDateTime expiresAt,

    LocalDateTime paidAt,
    LocalDateTime cancelledAt,

    List<BookingItemResponse> items,

    LocalDateTime createdAt

) {
    /**
     * Factory method từ entity Booking.
     * Lưu ý: items phải đã được load (không lazy) khi gọi method này.
     */
    public static BookingResponse from(Booking booking) {
        List<BookingItemResponse> itemResponses = booking.getItems().stream()
            .map(BookingItemResponse::from)
            .toList();

        return new BookingResponse(
            booking.getId(),
            booking.getConcert().getId(),
            booking.getConcert().getName(),
            booking.getStatus(),
            booking.getTotalAmount(),
            booking.getFinalAmount(),
            booking.getDiscountAmount(),
            booking.getVoucher() != null ? booking.getVoucher().getCode() : null,
            booking.getExpiresAt(),
            booking.getPaidAt(),
            booking.getCancelledAt(),
            itemResponses,
            booking.getCreatedAt()
        );
    }
}
