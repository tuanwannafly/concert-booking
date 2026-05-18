package com.geekup.concertbooking.module.ops;

import com.geekup.concertbooking.config.AppProperties;
import com.geekup.concertbooking.entity.*;
import com.geekup.concertbooking.module.booking.BookingItemRepository;
import com.geekup.concertbooking.module.booking.BookingRepository;
import com.geekup.concertbooking.module.concert.ConcertRepository;
import com.geekup.concertbooking.module.concert.TicketCategoryRepository;
import com.geekup.concertbooking.module.ops.dto.OpsBookingDTO.*;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import com.geekup.concertbooking.shared.enums.UserRole;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpsBookingServiceTest {

    @Mock private BookingRepository        bookingRepository;
    @Mock private BookingItemRepository    bookingItemRepository;
    @Mock private ConcertRepository        concertRepository;
    @Mock private TicketCategoryRepository ticketCategoryRepository;
    @Mock private AppProperties            appProperties;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OpsBookingService opsBookingService;

    // ── fixtures ─────────────────────────────────────────────────────────────

    private User         customer;
    private Concert      concert;
    private TicketCategory vipCat;
    private Booking      paidBooking;
    private Booking      pendingBooking;
    private Booking      completedBooking;
    private Booking      expiredBooking;

    @BeforeEach
    void setUp() {
        customer = User.builder()
            .id(1L).email("customer@test.com").role(UserRole.CUSTOMER).build();

        concert = Concert.builder()
            .id(10L).name("Rock Night").status(ConcertStatus.PUBLISHED).build();

        vipCat = TicketCategory.builder()
            .id(100L).concert(concert).name("VIP")
            .price(BigDecimal.valueOf(1_000_000))
            .totalQuantity(200).availableQuantity(150).maxPerBooking(4).build();

        paidBooking = Booking.builder()
            .id(1L).user(customer).concert(concert)
            .status(BookingStatus.PAID)
            .totalAmount(BigDecimal.valueOf(1_000_000))
            .finalAmount(BigDecimal.valueOf(1_000_000))
            .isSuspicious(false)
            .createdAt(LocalDateTime.now().minusHours(1))
            .expiresAt(LocalDateTime.now().plusMinutes(14))
            .build();

        pendingBooking = Booking.builder()
            .id(2L).user(customer).concert(concert)
            .status(BookingStatus.PENDING)
            .totalAmount(BigDecimal.valueOf(500_000))
            .finalAmount(BigDecimal.valueOf(500_000))
            .isSuspicious(false)
            .createdAt(LocalDateTime.now().minusMinutes(5))
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .build();

        completedBooking = Booking.builder()
            .id(3L).user(customer).concert(concert)
            .status(BookingStatus.COMPLETED)
            .totalAmount(BigDecimal.valueOf(1_000_000))
            .finalAmount(BigDecimal.valueOf(1_000_000))
            .isSuspicious(false)
            .createdAt(LocalDateTime.now().minusDays(1))
            .build();

        expiredBooking = Booking.builder()
            .id(4L).user(customer).concert(concert)
            .status(BookingStatus.EXPIRED)
            .totalAmount(BigDecimal.valueOf(500_000))
            .finalAmount(BigDecimal.valueOf(500_000))
            .isSuspicious(false)
            .createdAt(LocalDateTime.now().minusHours(2))
            .build();

        // Stub SecurityContext so resolveOperatorName() doesn't throw
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn("operator1@geekup.vn");
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        // Stub Redis prefix for inventory rollback
        AppProperties.RedisProperties redisProps = mock(AppProperties.RedisProperties.class);
        lenient().when(appProperties.getRedis()).thenReturn(redisProps);
        lenient().when(redisProps.getInventoryKeyPrefix()).thenReturn("inventory:ticket:");
    }

    // =========================================================================
    //  manualUpdateStatus — PAID → COMPLETED
    // =========================================================================

    @Test
    void manualUpdateStatus_paidToCompleted_succeeds() {
        given(bookingRepository.findById(1L)).willReturn(Optional.of(paidBooking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(bookingItemRepository.findByBookingId(1L)).willReturn(List.of());

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.COMPLETED, null);
        BookingDetailResponse response = opsBookingService.manualUpdateStatus(1L, req);

        assertThat(response.status()).isEqualTo(BookingStatus.COMPLETED);
        verify(bookingRepository).save(argThat(b -> b.getStatus() == BookingStatus.COMPLETED));
    }

    // =========================================================================
    //  manualUpdateStatus — COMPLETED is terminal
    // =========================================================================

    @Test
    void manualUpdateStatus_completedToAny_throwsInvalidStatus() {
        given(bookingRepository.findById(3L)).willReturn(Optional.of(completedBooking));

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.CANCELLED, "test");

        assertThatThrownBy(() -> opsBookingService.manualUpdateStatus(3L, req))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_INVALID_STATUS);

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void manualUpdateStatus_expiredToAny_throwsInvalidStatus() {
        given(bookingRepository.findById(4L)).willReturn(Optional.of(expiredBooking));

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.CANCELLED, "test");

        assertThatThrownBy(() -> opsBookingService.manualUpdateStatus(4L, req))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_INVALID_STATUS);
    }

    // =========================================================================
    //  manualUpdateStatus — cancel PENDING restores inventory
    // =========================================================================

    @Test
    void manualUpdateStatus_cancelPendingBooking_restoresRedisAndDbInventory() {
        BookingItem item = BookingItem.builder()
            .id(200L).booking(pendingBooking).ticketCategory(vipCat)
            .quantity(2).unitPrice(BigDecimal.valueOf(1_000_000))
            .subtotal(BigDecimal.valueOf(2_000_000))
            .build();

        given(bookingRepository.findById(2L)).willReturn(Optional.of(pendingBooking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(bookingItemRepository.findByBookingId(2L)).willReturn(List.of(item));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(vipCat));
        given(ticketCategoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.CANCELLED, "Fraud detected");
        BookingDetailResponse response = opsBookingService.manualUpdateStatus(2L, req);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo("Fraud detected");

        // Redis inventory must be incremented back by 2
        verify(valueOperations).increment("inventory:ticket:100", 2);

        // DB availableQuantity must be incremented back: 150 + 2 = 152
        verify(ticketCategoryRepository).save(argThat(cat -> cat.getAvailableQuantity() == 152));
    }

    @Test
    void manualUpdateStatus_cancelPaidBooking_doesNotRestoreInventory() {
        // PAID booking — inventory already "consumed", should NOT be rolled back
        given(bookingRepository.findById(1L)).willReturn(Optional.of(paidBooking));
        given(bookingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(bookingItemRepository.findByBookingId(1L)).willReturn(List.of());

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.CANCELLED, "Ops override");
        opsBookingService.manualUpdateStatus(1L, req);

        // No Redis or DB inventory modification expected
        verifyNoInteractions(redisTemplate);
        verify(ticketCategoryRepository, never()).save(any());
    }

    // =========================================================================
    //  manualUpdateStatus — invalid target transitions
    // =========================================================================

    @Test
    void manualUpdateStatus_toWaitingPayment_throwsInvalidStatus() {
        given(bookingRepository.findById(1L)).willReturn(Optional.of(paidBooking));

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.WAITING_PAYMENT, null);

        assertThatThrownBy(() -> opsBookingService.manualUpdateStatus(1L, req))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_INVALID_STATUS);
    }

    @Test
    void manualUpdateStatus_pendingToCompleted_throwsInvalidStatus() {
        // PENDING → COMPLETED is not allowed (must go through PAID first)
        given(bookingRepository.findById(2L)).willReturn(Optional.of(pendingBooking));

        UpdateBookingStatusRequest req = new UpdateBookingStatusRequest(BookingStatus.COMPLETED, null);

        assertThatThrownBy(() -> opsBookingService.manualUpdateStatus(2L, req))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_INVALID_STATUS);
    }

    // =========================================================================
    //  getSuspiciousBookings
    // =========================================================================

    @Test
    void getSuspiciousBookings_returnsPagedResults() {
        Booking suspicious = Booking.builder()
            .id(99L).user(customer).concert(concert)
            .status(BookingStatus.PENDING)
            .totalAmount(BigDecimal.valueOf(500_000))
            .finalAmount(BigDecimal.valueOf(500_000))
            .isSuspicious(true)
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusMinutes(15))
            .build();

        PageRequest pageable = PageRequest.of(0, 20);
        given(bookingRepository.findByIsSuspiciousTrue(pageable))
            .willReturn(new PageImpl<>(List.of(suspicious)));

        Page<BookingListResponse> result = opsBookingService.getSuspiciousBookings(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).isSuspicious()).isTrue();
        assertThat(result.getContent().get(0).id()).isEqualTo(99L);
    }

    @Test
    void getSuspiciousBookings_noSuspicious_returnsEmptyPage() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(bookingRepository.findByIsSuspiciousTrue(pageable))
            .willReturn(Page.empty());

        Page<BookingListResponse> result = opsBookingService.getSuspiciousBookings(pageable);

        assertThat(result.isEmpty()).isTrue();
    }

    // =========================================================================
    //  getBookingDetail
    // =========================================================================

    @Test
    void getBookingDetail_existingBooking_returnsFullDetail() {
        given(bookingRepository.findById(1L)).willReturn(Optional.of(paidBooking));
        given(bookingItemRepository.findByBookingId(1L)).willReturn(List.of());

        BookingDetailResponse response = opsBookingService.getBookingDetail(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(BookingStatus.PAID);
        assertThat(response.userEmail()).isEqualTo("customer@test.com");
    }

    @Test
    void getBookingDetail_notFound_throwsAppException() {
        given(bookingRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> opsBookingService.getBookingDetail(999L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
    }
}