package com.geekup.concertbooking.module.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekup.concertbooking.config.AppProperties;
import com.geekup.concertbooking.entity.*;
import com.geekup.concertbooking.module.auth.UserRepository;
import com.geekup.concertbooking.module.booking.dto.*;
import com.geekup.concertbooking.module.concert.ConcertRepository;
import com.geekup.concertbooking.module.concert.TicketCategoryRepository;
import com.geekup.concertbooking.module.voucher.VoucherRepository;
import com.geekup.concertbooking.module.voucher.VoucherUsageRepository;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import com.geekup.concertbooking.shared.enums.DiscountType;
import com.geekup.concertbooking.shared.enums.UserRole;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingItemRepository bookingItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private ConcertRepository concertRepository;
    @Mock private TicketCategoryRepository ticketCategoryRepository;
    @Mock private VoucherRepository voucherRepository;
    @Mock private VoucherUsageRepository voucherUsageRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private AppProperties appProperties;
    @Mock private ObjectMapper objectMapper;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private BookingService bookingService;

    // ── common fixtures ──────────────────────────────────────────────────────

    private User mockUser;
    private Concert mockConcert;
    private TicketCategory mockCategory;
    private AppProperties.RedisProperties mockRedisProps;
    private AppProperties.Booking mockBookingProps;

    @BeforeEach
    void setUp() {
        mockUser = User.builder()
                .id(1L)
                .email("customer1@test.com")
                .fullName("Test Customer")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .build();

        mockConcert = Concert.builder()
                .id(10L)
                .name("Anh Trai Say Hi")
                .status(ConcertStatus.PUBLISHED)
                .eventDate(LocalDateTime.now().plusDays(30))
                .build();

        mockCategory = TicketCategory.builder()
                .id(100L)
                .concert(mockConcert)
                .name("VIP")
                .price(new BigDecimal("500000"))
                .availableQuantity(50)
                .maxPerBooking(4)
                .version(0)
                .build();

        // Stub AppProperties
        mockRedisProps = new AppProperties.RedisProperties();
        mockBookingProps = new AppProperties.Booking();
        lenient().when(appProperties.getRedis()).thenReturn(mockRedisProps);
        lenient().when(appProperties.getBooking()).thenReturn(mockBookingProps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CreateBookingRequest buildRequest(Long categoryId, int quantity) {
        return new CreateBookingRequest(null, List.of(new BookingItemRequest(categoryId, quantity)));
    }

    private Booking buildSavedBooking(BigDecimal total) {
        return Booking.builder()
                .id(999L)
                .user(mockUser)
                .concert(mockConcert)
                .status(BookingStatus.PENDING)
                .totalAmount(total)
                .finalAmount(total)
                .idempotencyKey("test-idem-key")
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .isSuspicious(false)
                .build();
    }

    // =========================================================================
    // Test 1: createBooking_success_returnsBookingResponse
    // =========================================================================

    @Test
    void createBooking_success_returnsBookingResponse() throws Exception {
        // Arrange — happy path: Redis has inventory, concert published, category valid
        String idemKey = "test-idem-key";
        BigDecimal expectedTotal = new BigDecimal("1000000"); // 500_000 × 2

        given(valueOperations.get(anyString())).willReturn(null);             // no cache hit
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategory));
        given(valueOperations.decrement(anyString(), anyLong())).willReturn(48L); // remaining ≥ 0

        Booking savedBooking = buildSavedBooking(expectedTotal);
        given(bookingRepository.save(any(Booking.class))).willReturn(savedBooking);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":999}");

        CreateBookingRequest request = buildRequest(100L, 2);

        // Act
        BookingResponse result = bookingService.createBooking(request, 1L, idemKey);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(result.totalAmount()).isEqualByComparingTo(expectedTotal);

        // Verify Redis DECRBY was called once (one category item)
        verify(valueOperations, times(1)).decrement(
                eq("inventory:ticket:100"), eq(2L));
    }

    // =========================================================================
    // Test 2: createBooking_soldOut_throwsAppException
    // =========================================================================

    @Test
    void createBooking_soldOut_throwsAppException() {
        // Arrange — Redis DECRBY returns negative, indicating sold out
        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategory));
        given(valueOperations.decrement(anyString(), anyLong())).willReturn(-1L); // sold out!

        CreateBookingRequest request = buildRequest(100L, 2);

        // Act & Assert
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, "idem-key-sold-out"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_SOLD_OUT);

        // Verify rollback: increment called to restore what was decremented
        verify(valueOperations, atLeastOnce()).increment(eq("inventory:ticket:100"), anyLong());

        // Verify booking was NOT persisted
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    // =========================================================================
    // Test 3: createBooking_idempotency_returnsCachedResponse
    // =========================================================================

    @Test
    void createBooking_idempotency_returnsCachedResponse() throws Exception {
        // Arrange — Redis cache hit returns a previously cached BookingResponse
        String idemKey = "already-processed-key";
        String cachedJson = "{\"id\":777,\"status\":\"PENDING\"}";

        given(valueOperations.get("idem:booking:" + idemKey)).willReturn(cachedJson);

        BookingResponse cachedResponse = new BookingResponse(
                777L, 10L, "Concert", BookingStatus.PENDING,
                new BigDecimal("500000"), new BigDecimal("500000"),
                null, null, LocalDateTime.now().plusMinutes(15),
                null, null, List.of(), LocalDateTime.now());
        given(objectMapper.readValue(cachedJson, BookingResponse.class)).willReturn(cachedResponse);

        CreateBookingRequest request = buildRequest(100L, 1);

        // Act
        BookingResponse result = bookingService.createBooking(request, 1L, idemKey);

        // Assert — returned the exact cached booking
        assertThat(result.id()).isEqualTo(777L);

        // Verify NO inventory decrement happened (early return on cache hit)
        verify(valueOperations, never()).decrement(anyString(), anyLong());

        // Verify booking was NOT saved again
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    // =========================================================================
    // Test 4: cancelBooking_success_rollbacksInventory
    // =========================================================================

    @Test
    void cancelBooking_success_rollbacksInventory() {
        // Arrange — booking is PENDING, user owns it
        BookingItem item = BookingItem.builder()
                .ticketCategory(mockCategory)
                .quantity(2)
                .unitPrice(new BigDecimal("500000"))
                .build();

        Booking booking = Booking.builder()
                .id(200L)
                .user(mockUser)
                .concert(mockConcert)
                .status(BookingStatus.PENDING)
                .totalAmount(new BigDecimal("1000000"))
                .finalAmount(new BigDecimal("1000000"))
                .idempotencyKey("cancel-idem-key")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .isSuspicious(false)
                .build();
        booking.getItems().add(item);
        item.setBooking(booking);

        given(bookingRepository.findById(200L)).willReturn(Optional.of(booking));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategory));
        given(bookingRepository.save(any(Booking.class))).willReturn(booking);

        // Act
        BookingResponse result = bookingService.cancelBooking(200L, 1L);

        // Assert — booking status changed to CANCELLED
        assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);

        // Verify Redis inventory increment (rollback) was called with category 100, qty 2
        verify(valueOperations, times(1)).increment(eq("inventory:ticket:100"), eq(2L));

        // Verify DB availableQuantity was updated (ticketCategoryRepository.save called)
        verify(ticketCategoryRepository, atLeastOnce()).save(
                argThat(cat -> cat.getAvailableQuantity() == 52)); // 50 + 2
    }

    // =========================================================================
    // Test 5: pay_bookingExpired_throwsAppException
    // =========================================================================

    @Test
    void pay_bookingExpired_throwsAppException() {
        // Arrange — booking PENDING but expiresAt is 10 minutes in the past
        Booking expiredBooking = Booking.builder()
                .id(300L)
                .user(mockUser)
                .concert(mockConcert)
                .status(BookingStatus.PENDING)
                .totalAmount(new BigDecimal("500000"))
                .finalAmount(new BigDecimal("500000"))
                .idempotencyKey("expired-idem")
                .expiresAt(LocalDateTime.now().minusMinutes(10)) // already expired!
                .isSuspicious(false)
                .build();

        given(bookingRepository.findById(300L)).willReturn(Optional.of(expiredBooking));
        given(bookingRepository.save(any(Booking.class))).willReturn(expiredBooking);

        // Act & Assert
        assertThatThrownBy(() -> bookingService.pay(300L, 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BOOKING_EXPIRED);
    }

    // =========================================================================
    // Test 6: createBooking_withVoucher_appliesDiscount
    // =========================================================================

    @Test
    void createBooking_withVoucher_appliesDiscount() throws Exception {
        // Arrange — PERCENT voucher 10% on 1,000,000đ order
        String idemKey = "voucher-idem-key";
        BigDecimal orderTotal = new BigDecimal("1000000"); // 500_000 × 2

        Voucher voucher = Voucher.builder()
                .id(50L)
                .code("FLASHSALE10")
                .discountType(DiscountType.PERCENT)
                .discountValue(new BigDecimal("10"))
                .maxUses(100)
                .usedCount(0)
                .minOrderAmount(new BigDecimal("500000"))
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .isActive(true)
                .build();

        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategory));
        given(valueOperations.decrement(anyString(), anyLong())).willReturn(48L);

        // Voucher validation stubs
        given(voucherRepository.findByCodeAndIsActiveTrue("FLASHSALE10")).willReturn(Optional.of(voucher));
        // Redis lock returns null (not acquired), but we simulate success via true
        given(valueOperations.setIfAbsent(anyString(), anyString(), any())).willReturn(true);
        given(voucherUsageRepository.existsByVoucherIdAndUserId(50L, 1L)).willReturn(false);
        given(voucherRepository.save(any(Voucher.class))).willReturn(voucher);
        given(voucherUsageRepository.save(any(VoucherUsage.class))).willReturn(null);

        // Booking save returns entity with correct amounts
        BigDecimal expectedDiscount = new BigDecimal("100000"); // 10% of 1_000_000
        BigDecimal expectedFinal    = new BigDecimal("900000");

        Booking savedBooking = Booking.builder()
                .id(888L)
                .user(mockUser)
                .concert(mockConcert)
                .status(BookingStatus.PENDING)
                .totalAmount(orderTotal)
                .discountAmount(expectedDiscount)
                .finalAmount(expectedFinal)
                .voucher(voucher)
                .idempotencyKey(idemKey)
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .isSuspicious(false)
                .build();

        given(bookingRepository.save(any(Booking.class))).willReturn(savedBooking);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":888}");

        CreateBookingRequest request = new CreateBookingRequest(
                "FLASHSALE10", List.of(new BookingItemRequest(100L, 2)));

        // Act
        BookingResponse result = bookingService.createBooking(request, 1L, idemKey);

        // Assert discount applied correctly
        assertThat(result.discountAmount()).isEqualByComparingTo(expectedDiscount);
        assertThat(result.finalAmount()).isEqualByComparingTo(expectedFinal);

        // Verify VoucherUsage was persisted
        verify(voucherUsageRepository, times(1)).save(any(VoucherUsage.class));
    }

    // =========================================================================
    // Test 7: createBooking_concertNotPublished_throwsAppException
    // =========================================================================

    @Test
    void createBooking_concertNotPublished_throwsAppException() {
        // Arrange — concert is still DRAFT, not PUBLISHED
        Concert draftConcert = Concert.builder()
                .id(20L)
                .name("Unreleased Concert")
                .status(ConcertStatus.DRAFT)  // not published!
                .build();

        TicketCategory draftCategory = TicketCategory.builder()
                .id(200L)
                .concert(draftConcert)
                .name("Standard")
                .price(new BigDecimal("300000"))
                .availableQuantity(100)
                .maxPerBooking(4)
                .version(0)
                .build();

        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(200L)).willReturn(Optional.of(draftCategory));

        CreateBookingRequest request = buildRequest(200L, 1);

        // Act & Assert
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, "draft-concert-idem"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONCERT_NOT_PUBLISHED);

        // Verify Redis DECRBY was NOT called — validation must fail before touching Redis
        verify(valueOperations, never()).decrement(anyString(), anyLong());
    }
}