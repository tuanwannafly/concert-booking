package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

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
     * Ví dụ: lấy tất cả booking PAID + COMPLETED của concert X.
     */
    Page<Booking> findByConcertIdAndStatusIn(Long concertId, List<BookingStatus> statuses, Pageable pageable);

    /**
     * Tìm tất cả booking đã hết hạn nhưng chưa được đánh dấu EXPIRED.
     * Dùng bởi BookingExpiryScheduler.
     *
     * Điều kiện: status IN (PENDING, WAITING_PAYMENT) AND expiresAt < :now
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
     * Dùng để phát hiện hành vi đặt vé bất thường (suspicious detection).
     *
     * Chỉ đếm các booking chưa bị cancel/expire để tránh false-positive
     * từ các booking đã bị rollback.
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
}
