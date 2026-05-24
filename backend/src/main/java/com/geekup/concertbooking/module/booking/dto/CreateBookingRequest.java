package com.geekup.concertbooking.module.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body để tạo một booking mới.
 *
 * Client phải gửi kèm header:
 *   X-Idempotency-Key: <UUID v4>
 *
 * để đảm bảo idempotency (retry-safe). Key được đọc ở controller
 * và truyền vào BookingService.createBooking().
 */
public record CreateBookingRequest(

    /**
     * Mã voucher muốn áp dụng. Optional — có thể null hoặc không gửi.
     */
    String voucherCode,

    /**
     * Danh sách loại vé và số lượng muốn đặt.
     * Phải có ít nhất 1 item.
     */
    @NotEmpty(message = "Danh sách vé không được rỗng")
    @Valid
    List<BookingItemRequest> items

) {}
