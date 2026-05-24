package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.entity.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler tự động expire các booking quá hạn.
 *
 * Chạy mỗi 60 giây (fixedDelay = 60000ms — tính từ lúc lần chạy trước kết thúc,
 * tránh overlap nếu lần chạy trước tốn nhiều thời gian hơn 60s).
 *
 * Logic: tìm tất cả booking có status PENDING/WAITING_PAYMENT và expiresAt < now,
 * rồi chuyển sang EXPIRED đồng thời hoàn trả inventory.
 *
 * Yêu cầu: @EnableScheduling trên ConcertBookingApplication (đã enable).
 *
 * NOTE: Trong môi trường multi-instance (k8s, multiple pods), scheduler này
 * sẽ chạy song song trên mọi pod. Phase sau nên dùng ShedLock hoặc
 * Spring Batch để đảm bảo chỉ 1 instance xử lý mỗi batch.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final BookingService    bookingService;

    /**
     * Tìm và expire tất cả booking quá hạn.
     *
     * fixedDelay đảm bảo khoảng cách tối thiểu 60s giữa các lần chạy
     * (không phải fixed rate — tránh thundering herd nếu DB chậm).
     */
    @Scheduled(fixedDelay = 60_000)
    public void expireOverdueBookings() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("BookingExpiryScheduler running at {}", now);

        List<Booking> expiredBookings;
        try {
            expiredBookings = bookingRepository.findExpiredBookings(now);
        } catch (Exception e) {
            log.error("Failed to query expired bookings: {}", e.getMessage(), e);
            return;
        }

        if (expiredBookings.isEmpty()) {
            log.debug("No expired bookings found");
            return;
        }

        log.info("Found {} expired booking(s) to process", expiredBookings.size());

        int successCount = 0;
        int failCount = 0;

        for (Booking booking : expiredBookings) {
            try {
                // Gọi service trong transaction riêng để mỗi booking fail độc lập
                bookingService.expireBooking(booking);

                log.info("Expired booking {} for user {} (concert {})",
                    booking.getId(),
                    booking.getUser().getId(),
                    booking.getConcert().getId());

                successCount++;

            } catch (Exception e) {
                // Log error nhưng không dừng loop — tiếp tục xử lý booking tiếp theo
                log.error("Failed to expire booking {}: {}", booking.getId(), e.getMessage(), e);
                failCount++;
            }
        }

        log.info("BookingExpiryScheduler done: {} expired, {} failed", successCount, failCount);
    }
}
