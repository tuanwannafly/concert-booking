package com.geekup.concertbooking.module.ops.dto;

import com.geekup.concertbooking.module.booking.dto.BookingItemResponse;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Nhóm tất cả DTO cho Ops Booking module vào 1 file.
 * Dùng inner records để tránh phân tán file và dễ import:
 *   import com.geekup.concertbooking.module.ops.dto.OpsBookingDTO.*;
 */
public class OpsBookingDTO {

    // =========================================================================
    //  BookingListResponse — danh sách booking (gọn, không có items)
    // =========================================================================

    /**
     * Response cho list endpoint — đủ thông tin để ops filter và triage
     * nhưng không load items để tránh N+1.
     */
    public record BookingListResponse(

        Long id,
        Long userId,
        String userEmail,
        Long concertId,
        String concertName,
        BookingStatus status,

        BigDecimal totalAmount,
        BigDecimal finalAmount,

        /** null nếu booking không dùng voucher */
        String voucherCode,

        Boolean isSuspicious,
        LocalDateTime createdAt,

        /** null với PAID / COMPLETED / CANCELLED / EXPIRED */
        LocalDateTime expiresAt

    ) {}

    // =========================================================================
    //  BookingDetailResponse — chi tiết 1 booking (đầy đủ)
    // =========================================================================

    /**
     * Response cho detail endpoint — thêm items, audit timestamps, cancelReason.
     * Ops cần chi tiết này khi điều tra hoặc xử lý thủ công.
     */
    public record BookingDetailResponse(

        // --- identity ---
        Long id,
        Long userId,
        String userEmail,
        Long concertId,
        String concertName,

        // --- trạng thái & tiền ---
        BookingStatus status,
        BigDecimal totalAmount,
        BigDecimal finalAmount,
        BigDecimal discountAmount,
        String voucherCode,
        Boolean isSuspicious,

        // --- items ---
        List<BookingItemResponse> items,

        // --- timestamps ---
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime cancelledAt,

        // --- ops audit ---
        String cancelReason

    ) {}

    // =========================================================================
    //  UpdateBookingStatusRequest — ops update status thủ công
    // =========================================================================

    /**
     * Body cho PUT /{id}/status.
     * reason nullable — bắt buộc điền khi chuyển sang CANCELLED (validate ở service).
     */
    public record UpdateBookingStatusRequest(

        @NotNull(message = "status không được để trống")
        BookingStatus status,

        /** Lý do huỷ — lưu vào cancelReason. Bắt buộc khi status = CANCELLED. */
        String reason

    ) {}

    // =========================================================================
    //  BookingStatsResponse — dashboard overview theo concert
    // =========================================================================

    /**
     * Tổng hợp số liệu cho Ops Dashboard.
     * totalRevenue = SUM(finalAmount) của các booking PAID + COMPLETED.
     */
    public record BookingStatsResponse(

        Long concertId,
        String concertName,

        long totalBookings,
        long pendingCount,
        long waitingPaymentCount,
        long paidCount,
        long completedCount,
        long cancelledCount,
        long expiredCount,

        /** Tổng doanh thu thực thu (PAID + COMPLETED). BigDecimal.ZERO nếu chưa có. */
        BigDecimal totalRevenue

    ) {}
}
