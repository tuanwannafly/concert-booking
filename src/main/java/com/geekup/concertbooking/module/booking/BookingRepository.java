package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;  // ← NEW import
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor; // ← NEW import
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * DIFF từ Phase trước:
 *
 *  1. Extends JpaSpecificationExecutor<Booking>  — cho phép findAll(Specification, Pageable)
 *     dùng bởi OpsBookingService.listBookings()
 *
 *  2. countByConcertId()                         — tổng booking của concert
 *  3. countByConcertIdAndStatus()                — count theo concert + status
 *  4. sumFinalAmountByConcertIdAndStatusIn()     — tổng doanh thu thực thu
 *
 *  Tất cả method cũ giữ nguyên — không breaking change.
 */
@Repository
public interface BookingRepository
        extends JpaRepository<Booking, Long>,
                JpaSpecificationExecutor<Booking> {  // ← THÊM extends này

    // =========================================================================
    //  CÁC METHOD CŨ — GIỮ NGUYÊN
    // =========================================================================

    /**
     * Tìm booking theo idempotency key — dùng để dedup request.
     */
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /**
     * Lịch sử booking của 1 user, phân trang.
     */
    Page<Booking> findByUserId(Long userId, Pageable pageable);

    /**
     * Ops dashboard: lọc các booking bị đánh dấu suspicious.
     */
    Page<Booking> findByIsSuspiciousTrue(Pageable pageable);

    /**
     * Ops / Admin: tìm booking theo concert và danh sách status.
     */
    Page<Booking> findByConcertIdAndStatusIn(Long concertId, List<BookingStatus> statuses, Pageable pageable);

    /**
     * Tìm tất cả booking đã hết hạn nhưng chưa được đánh dấu EXPIRED.
     * Dùng bởi BookingExpiryScheduler.
     */
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status IN (
            com.geekup.concertbooking.shared.enums.BookingStatus.PENDING,
            com.geekup.concertbooking.shared.enums.BookingStatus.WAITING_PAYMENT
        )
        AND b.expiresAt < :now
        """)
    List<Booking> findExpiredBookings(@Param("now") LocalDateTime now);

    /**
     * Đếm số booking gần đây của 1 user kể từ mốc thời gian :since.
     */
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.user.id = :userId
          AND b.createdAt >= :since
          AND b.status NOT IN (
              com.geekup.concertbooking.shared.enums.BookingStatus.CANCELLED,
              com.geekup.concertbooking.shared.enums.BookingStatus.EXPIRED
          )
        """)
    long countRecentBookingsByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    // =========================================================================
    //  CÁC METHOD MỚI — DÙNG BỞI OpsBookingService (Phase 5)
    // =========================================================================

    /**
     * Tổng số booking của 1 concert (mọi status).
     * Dùng cho BookingStatsResponse.totalBookings.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.concert.id = :concertId")
    long countByConcertId(@Param("concertId") Long concertId);

    /**
     * Đếm booking của concert theo status cụ thể.
     * Gọi 1 lần cho mỗi status (PENDING, WAITING_PAYMENT, PAID, COMPLETED, CANCELLED, EXPIRED).
     *
     * Lý do không dùng derived method countByConcertIdAndStatus(): cần JPQL
     * explicit để đảm bảo type-safe với enum và dễ debug explain plan.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.concert.id = :concertId AND b.status = :status")
    long countByConcertIdAndStatus(
        @Param("concertId") Long concertId,
        @Param("status")    BookingStatus status
    );

    /**
     * Tổng doanh thu thực thu (SUM finalAmount) của concert với các status chỉ định.
     * Thường gọi với statuses = [PAID, COMPLETED].
     *
     * COALESCE đảm bảo trả về null (không phải exception) khi chưa có booking nào —
     * service sẽ default sang BigDecimal.ZERO.
     */
    @Query("""
        SELECT COALESCE(SUM(b.finalAmount), 0)
        FROM Booking b
        WHERE b.concert.id = :concertId
          AND b.status IN :statuses
        """)
    BigDecimal sumFinalAmountByConcertIdAndStatusIn(
        @Param("concertId") Long concertId,
        @Param("statuses")  List<BookingStatus> statuses
    );
}
