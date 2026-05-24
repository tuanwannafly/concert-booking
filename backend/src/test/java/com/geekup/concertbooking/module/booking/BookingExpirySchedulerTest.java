package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.entity.User;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import com.geekup.concertbooking.shared.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingExpiryScheduler.
 *
 * The scheduler is the only mechanism that releases inventory for abandoned bookings.
 * Its correctness requirements are:
 *  1. Calls expireBooking() exactly once per expired booking found.
 *  2. A failure on one booking must not abort the loop (other bookings must still be processed).
 *  3. A DB query failure must not propagate — if it did, Spring would stop rescheduling.
 *  4. When there is nothing to expire, expireBooking() is never called.
 */
@ExtendWith(MockitoExtension.class)
class BookingExpirySchedulerTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService    bookingService;

    @InjectMocks
    private BookingExpiryScheduler scheduler;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Booking expiredBooking(Long id) {
        User user = User.builder()
                .id(1L).email("u@test.com").role(UserRole.CUSTOMER).isActive(true).build();
        Concert concert = Concert.builder()
                .id(10L).name("Test Concert").status(ConcertStatus.PUBLISHED).build();
        return Booking.builder()
                .id(id).user(user).concert(concert)
                .status(BookingStatus.WAITING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(500_000))
                .finalAmount(BigDecimal.valueOf(500_000))
                .idempotencyKey("idem-" + id)
                .expiresAt(LocalDateTime.now().minusMinutes(5))  // expired 5 min ago
                .isSuspicious(false).build();
    }

    // =========================================================================
    // Test 1 — No expired bookings found → expireBooking is never called
    // =========================================================================

    @Test
    void expireOverdueBookings_noneFound_doesNotCallExpireBooking() {
        // Arrange
        given(bookingRepository.findExpiredBookings(any(LocalDateTime.class)))
                .willReturn(List.of());

        // Act
        scheduler.expireOverdueBookings();

        // Assert
        verify(bookingService, never()).expireBooking(any(Booking.class));
    }

    // =========================================================================
    // Test 2 — Two expired bookings → each is processed exactly once
    // =========================================================================

    @Test
    void expireOverdueBookings_twoExpiredBookings_expiresBoth() {
        // Arrange
        Booking b1 = expiredBooking(1L);
        Booking b2 = expiredBooking(2L);
        given(bookingRepository.findExpiredBookings(any(LocalDateTime.class)))
                .willReturn(List.of(b1, b2));
        doNothing().when(bookingService).expireBooking(any(Booking.class));

        // Act
        scheduler.expireOverdueBookings();

        // Assert — exactly one call per booking, no extras
        verify(bookingService, times(1)).expireBooking(b1);
        verify(bookingService, times(1)).expireBooking(b2);
    }

    // =========================================================================
    // Test 3 — Middle booking throws during expiry; other two must still complete
    //
    // This is the resilience guarantee: inventory locked by b2 would be
    // permanently unreleased if the loop aborted on the first error.
    // =========================================================================

    @Test
    void expireOverdueBookings_oneBookingFails_continuesProcessingRemainder() {
        // Arrange
        Booking b1 = expiredBooking(1L);
        Booking b2 = expiredBooking(2L);  // will throw during expiry
        Booking b3 = expiredBooking(3L);

        given(bookingRepository.findExpiredBookings(any(LocalDateTime.class)))
                .willReturn(List.of(b1, b2, b3));

        doNothing().when(bookingService).expireBooking(b1);
        doThrow(new RuntimeException("Transient DB error during b2 expiry"))
                .when(bookingService).expireBooking(b2);
        doNothing().when(bookingService).expireBooking(b3);

        // Act — must complete without throwing
        assertThatNoException().isThrownBy(() -> scheduler.expireOverdueBookings());

        // Assert — all three attempted; b3 must not be skipped because b2 failed
        verify(bookingService, times(1)).expireBooking(b1);
        verify(bookingService, times(1)).expireBooking(b2);
        verify(bookingService, times(1)).expireBooking(b3);
    }

    // =========================================================================
    // Test 4 — The DB query itself throws
    //
    // If findExpiredBookings() fails (e.g. DB restart), the scheduler must
    // catch the exception and return quietly.  A propagated exception here
    // would cause Spring's @Scheduled to stop re-scheduling the method entirely,
    // meaning NO bookings would ever expire again until the app restarts.
    // =========================================================================

    @Test
    void expireOverdueBookings_dbQueryFails_doesNotPropagateException() {
        // Arrange — DB completely unavailable
        given(bookingRepository.findExpiredBookings(any(LocalDateTime.class)))
                .willThrow(new RuntimeException("DB unavailable — connection pool exhausted"));

        // Act — must NOT propagate; scheduler must survive infrastructure failures
        assertThatNoException().isThrownBy(() -> scheduler.expireOverdueBookings());

        // Assert — expireBooking was never invoked (query never returned results)
        verify(bookingService, never()).expireBooking(any(Booking.class));
    }
}
