package com.geekup.concertbooking.module.booking.dto;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.shared.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response trả về sau khi thanh toán thành công.
 * Gọn hơn BookingResponse — chỉ chứa thông tin thanh toán thiết yếu.
 */
public record PaymentResponse(

    Long bookingId,
    BookingStatus status,
    LocalDateTime paidAt,
    BigDecimal finalAmount

) {
    public static PaymentResponse from(Booking booking) {
        return new PaymentResponse(
            booking.getId(),
            booking.getStatus(),
            booking.getPaidAt(),
            booking.getFinalAmount()
        );
    }
}
