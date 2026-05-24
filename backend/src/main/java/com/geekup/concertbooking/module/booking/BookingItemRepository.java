package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingItemRepository extends JpaRepository<BookingItem, Long> {

    /**
     * Lấy tất cả items của 1 booking.
     * Thường dùng khi cần load lazy items mà không muốn trigger N+1.
     */
    List<BookingItem> findByBookingId(Long bookingId);
}
