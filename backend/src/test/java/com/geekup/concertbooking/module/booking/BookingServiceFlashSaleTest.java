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
import com.geekup.concertbooking.shared.enums.*;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Edge-case tests for the flash-sale critical paths in BookingService.
 *
 * Covers scenarios intentionally left out of BookingServiceTest (happy paths):
 *  - Redis DECRBY ok but DB write fails           → inventory drift prevention
 *  - Multi-category partial rollback              → atomicity of Redis rollback
 *  - Optimistic lock conflict with retry          → @Version safety net
 *  - Optimistic lock retry also fails             → Redis rollback + propagation
 *  - Redis completely unavailable                 → graceful DB-only fallback
 *  - Suspicious booking detection                 → non-blocking flag, second save
 *  - VoucherUsage ordering after the FK bug fix   → booking.id must be non-null
 *  - Duplicate idempotency key hits DB            → BOOKING_ALREADY_EXISTS + rollback
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceFlashSaleTest {

    @Mock private BookingRepository         bookingRepository;
    @Mock private BookingItemRepository     bookingItemRepository;
    @Mock private UserRepository            userRepository;
    @Mock private ConcertRepository         concertRepository;
    @Mock private TicketCategoryRepository  ticketCategoryRepository;
    @Mock private VoucherRepository         voucherRepository;
    @Mock private VoucherUsageRepository    voucherUsageRepository;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private AppProperties             appProperties;
    @Mock private ObjectMapper              objectMapper;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private BookingService bookingService;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private User           mockUser;
    private Concert        mockConcert;
    private TicketCategory mockCategoryVip;       // id=100, "VIP",      500 000 ₫
    private TicketCategory mockCategoryStandard;  // id=101, "Standard", 300 000 ₫

    private static final String INV_KEY_100 = "inventory:ticket:100";
    private static final String INV_KEY_101 = "inventory:ticket:101";
    private static final String IDEM_KEY    = "flash-sale-idem-key";

    @BeforeEach
    void setUp() {
        mockUser = User.builder()
                .id(1L).email("customer1@test.com").fullName("Flash Customer")
                .role(UserRole.CUSTOMER).isActive(true).build();

        mockConcert = Concert.builder()
                .id(10L).name("Flash Sale Concert")
                .status(ConcertStatus.PUBLISHED)
                .eventDate(LocalDateTime.now().plusDays(7)).build();

        mockCategoryVip = TicketCategory.builder()
                .id(100L).concert(mockConcert).name("VIP")
                .price(new BigDecimal("500000"))
                .availableQuantity(50).maxPerBooking(4).version(0).build();

        mockCategoryStandard = TicketCategory.builder()
                .id(101L).concert(mockConcert).name("Standard")
                .price(new BigDecimal("300000"))
                .availableQuantity(100).maxPerBooking(4).version(0).build();

        // Wire AppProperties stubs used by every test
        AppProperties.RedisProperties redisProps = new AppProperties.RedisProperties();
        AppProperties.Booking bookingProps = new AppProperties.Booking();
        lenient().when(appProperties.getRedis()).thenReturn(redisProps);
        lenient().when(appProperties.getBooking()).thenReturn(bookingProps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Minimal stubs to get past validation + Redis DECRBY for a single-category request. */
    private void stubSingleCategoryBase(long redisRemaining) {
        given(valueOperations.get(anyString())).willReturn(null);   // no idempotency cache hit
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));
        given(valueOperations.decrement(eq(INV_KEY_100), anyLong())).willReturn(redisRemaining);
    }

    /** A booking entity with a real id, as if just returned by bookingRepository.save(). */
    private Booking persistedBooking(Long id, BigDecimal total) {
        return Booking.builder()
                .id(id).user(mockUser).concert(mockConcert)
                .status(BookingStatus.PENDING)
                .totalAmount(total).finalAmount(total)
                .idempotencyKey(IDEM_KEY)
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .isSuspicious(false).build();
    }

    // =========================================================================
    // Test 8 — Redis DECRBY succeeds, then DB write throws
    //
    // Scenario: network partition or DB overload hits AFTER Redis already
    // decremented the counter.  Without an explicit rollback the tickets would
    // be phantom-sold forever.
    // =========================================================================

    @Test
    void createBooking_dbWriteFailsAfterRedisDecrby_rollbacksRedisInventory() {
        // Arrange: Redis gate passes (remaining = 48), but DB explodes
        stubSingleCategoryBase(48L);
        given(bookingRepository.save(any(Booking.class)))
                .willThrow(new RuntimeException("DB connection lost mid-transaction"));

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act & Assert: exception propagates to the caller
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, IDEM_KEY))
                .isInstanceOf(RuntimeException.class);

        // The catch block in STEP 6 must restore Redis so tickets are re-sellable
        verify(valueOperations, times(1)).increment(eq(INV_KEY_100), eq(2L));
    }

    // =========================================================================
    // Test 9 — Two categories; first DECRBY ok, second returns -1 (sold out)
    //
    // Atomicity requirement: if ANY item in a multi-item booking cannot be
    // fulfilled, ALL previously decremented categories must be rolled back.
    // Partial fulfilment would leave Redis and DB out of sync.
    // =========================================================================

    @Test
    void createBooking_multipleCategories_secondSoldOut_rollbacksFirstCategory() {
        // Arrange
        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));
        given(ticketCategoryRepository.findById(101L)).willReturn(Optional.of(mockCategoryStandard));

        given(valueOperations.decrement(eq(INV_KEY_100), eq(2L))).willReturn(48L);  // ok
        given(valueOperations.decrement(eq(INV_KEY_101), eq(1L))).willReturn(-1L);  // sold out!

        CreateBookingRequest request = new CreateBookingRequest(null, List.of(
                new BookingItemRequest(100L, 2),
                new BookingItemRequest(101L, 1)));

        // Act & Assert
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, IDEM_KEY))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_SOLD_OUT);

        // Category 101: immediate rollback of the negative decrement
        verify(valueOperations, atLeastOnce()).increment(eq(INV_KEY_101), eq(1L));

        // Category 100: was successfully decremented before 101 failed — must also be restored
        verify(valueOperations, atLeastOnce()).increment(eq(INV_KEY_100), eq(2L));

        // DB must be completely untouched
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    // =========================================================================
    // Test 10 — Two categories, both succeed
    //
    // Positive multi-category baseline: verifies both DECRBY calls happen and
    // the correct total (500k×2 + 300k×1 = 1 300 000 ₫) is returned.
    // =========================================================================

    @Test
    void createBooking_multipleCategories_allSucceed_decrementsEachCategory() throws Exception {
        // Arrange
        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));
        given(ticketCategoryRepository.findById(101L)).willReturn(Optional.of(mockCategoryStandard));
        given(valueOperations.decrement(eq(INV_KEY_100), eq(2L))).willReturn(48L);
        given(valueOperations.decrement(eq(INV_KEY_101), eq(1L))).willReturn(99L);

        BigDecimal expectedTotal = new BigDecimal("1300000"); // 500k×2 + 300k×1
        Booking saved = persistedBooking(888L, expectedTotal);
        given(bookingRepository.save(any(Booking.class))).willReturn(saved);
        given(ticketCategoryRepository.save(any())).willReturn(mockCategoryVip);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":888}");

        CreateBookingRequest request = new CreateBookingRequest(null, List.of(
                new BookingItemRequest(100L, 2),
                new BookingItemRequest(101L, 1)));

        // Act
        BookingResponse result = bookingService.createBooking(request, 1L, IDEM_KEY);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.totalAmount()).isEqualByComparingTo(expectedTotal);

        verify(valueOperations, times(1)).decrement(INV_KEY_100, 2L);
        verify(valueOperations, times(1)).decrement(INV_KEY_101, 1L);
    }

    // =========================================================================
    // Test 11 — Optimistic lock conflict → service retries with fresh entity
    //
    // The @Version column on ticket_categories is the DB-layer safety net.
    // Under flash-sale concurrency two requests may both pass the Redis gate
    // and race to UPDATE the same row.  The loser gets OOLFE; the service
    // reloads the row (new version) and retries exactly once.
    // =========================================================================

    @Test
    void createBooking_optimisticLockConflict_retriesWithFreshEntityAndSucceeds() throws Exception {
        // Arrange
        stubSingleCategoryBase(48L);

        Booking saved = persistedBooking(999L, new BigDecimal("1000000"));
        given(bookingRepository.save(any(Booking.class))).willReturn(saved);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":999}");

        // First save attempt → Hibernate detects stale @Version → OOLFE
        // Second save attempt (with fresh entity) → success
        given(ticketCategoryRepository.save(any(TicketCategory.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(TicketCategory.class, 100L))
                .willReturn(mockCategoryVip);

        // Reload path inside updateCategoryQuantityWithRetry returns an up-to-date entity
        TicketCategory freshCategory = TicketCategory.builder()
                .id(100L).concert(mockConcert).name("VIP")
                .price(new BigDecimal("500000"))
                .availableQuantity(48)  // another request already committed 2 tickets
                .maxPerBooking(4).version(1).build();
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(freshCategory));

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act — the retry absorbs the conflict; booking completes successfully
        BookingResponse result = bookingService.createBooking(request, 1L, IDEM_KEY);

        assertThat(result).isNotNull();

        // ticketCategoryRepository.save() called twice: failed attempt + successful retry
        verify(ticketCategoryRepository, times(2)).save(any(TicketCategory.class));

        // Redis was NOT rolled back — the booking ultimately succeeded
        verify(valueOperations, never()).increment(eq(INV_KEY_100), anyLong());
    }

    // =========================================================================
    // Test 12 — Optimistic lock: both the original and the retry fail
    //
    // If the single retry also hits a version conflict, the exception propagates.
    // Redis inventory must still be rolled back so the counter stays consistent.
    // =========================================================================

    @Test
    void createBooking_optimisticLockConflictBothAttempts_rollbacksRedisAndThrows() {
        // Arrange
        stubSingleCategoryBase(48L);

        Booking saved = persistedBooking(999L, new BigDecimal("1000000"));
        given(bookingRepository.save(any(Booking.class))).willReturn(saved);

        // Both save attempts fail — rare but possible under very high contention
        given(ticketCategoryRepository.save(any(TicketCategory.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(TicketCategory.class, 100L));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act & Assert — OOLFE propagates out of the service
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, IDEM_KEY))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Redis counter must be restored: DB didn't commit, so the decrement is phantom
        verify(valueOperations, atLeastOnce()).increment(eq(INV_KEY_100), eq(2L));
    }

    // =========================================================================
    // Test 13 — Redis completely unavailable (throws on DECRBY)
    //
    // Redis is an accelerator, not a hard dependency.  When it's unreachable,
    // the service must degrade gracefully to DB-only optimistic locking rather
    // than rejecting every booking.
    // =========================================================================

    @Test
    void createBooking_redisUnavailable_fallsBackToDbLock() throws Exception {
        // Arrange
        given(valueOperations.get(anyString())).willReturn(null);  // idempotency: no cache
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));

        // Redis DECRBY fails hard — connection refused, not a soft miss
        given(valueOperations.decrement(anyString(), anyLong()))
                .willThrow(new RedisConnectionFailureException("Redis unreachable"));

        // DB path is healthy
        Booking saved = persistedBooking(777L, new BigDecimal("1000000"));
        given(bookingRepository.save(any(Booking.class))).willReturn(saved);
        given(ticketCategoryRepository.save(any())).willReturn(mockCategoryVip);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":777}");

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act — must NOT throw despite Redis being down
        BookingResponse result = bookingService.createBooking(request, 1L, IDEM_KEY);

        // Assert: booking was created via the DB fallback path
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(777L);
        verify(bookingRepository, atLeastOnce()).save(any(Booking.class));
    }

    // =========================================================================
    // Test 14 — User has > 3 bookings in the last 5 minutes → suspicious flag
    //
    // Suspicious detection is non-blocking: the booking is created normally AND
    // then saved a second time with isSuspicious = true so Ops can review it.
    // The customer should not see any error or degraded behaviour.
    // =========================================================================

    @Test
    void createBooking_moreThanThreeRecentBookings_flagsBookingAsSuspicious() throws Exception {
        // Arrange
        stubSingleCategoryBase(48L);

        Booking saved = persistedBooking(111L, new BigDecimal("1000000"));
        given(bookingRepository.save(any(Booking.class))).willReturn(saved);
        given(ticketCategoryRepository.save(any())).willReturn(mockCategoryVip);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":111}");

        // 4 bookings in the last 5 minutes — exceeds the threshold of 3
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(4L);

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act
        bookingService.createBooking(request, 1L, IDEM_KEY);

        // Assert — capture both save() calls and verify the second one sets isSuspicious=true
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, times(2)).save(captor.capture());

        List<Booking> allSaves = captor.getAllValues();
        Booking suspiciousSave = allSaves.get(1);  // second save = the flag-update
        assertThat(suspiciousSave.getIsSuspicious())
                .as("Second save must carry the suspicious flag")
                .isTrue();
    }

    // =========================================================================
    // Test 15 — VoucherUsage is saved AFTER bookingRepository.save()
    //
    // Before the fix, VoucherUsage was built with `booking` (id=null) and saved
    // before the booking was persisted — triggering TransientPropertyValueException
    // because VoucherUsage.booking_id is a NOT NULL FK → bookings.id.
    //
    // This test captures the VoucherUsage argument and asserts the booking
    // reference inside it already carries a real database id.
    // =========================================================================

    @Test
    void createBooking_withVoucher_voucherUsageSavedAfterBookingWithNonNullId() throws Exception {
        // Arrange
        given(valueOperations.get(anyString())).willReturn(null);
        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(ticketCategoryRepository.findById(100L)).willReturn(Optional.of(mockCategoryVip));
        given(valueOperations.decrement(anyString(), anyLong())).willReturn(48L);

        Voucher voucher = Voucher.builder()
                .id(50L).code("VIP200K")
                .discountType(DiscountType.FIXED).discountValue(new BigDecimal("200000"))
                .maxUses(50).usedCount(0)
                .minOrderAmount(new BigDecimal("500000"))
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .isActive(true).build();

        given(voucherRepository.findByCodeAndIsActiveTrue("VIP200K")).willReturn(Optional.of(voucher));
        given(valueOperations.setIfAbsent(anyString(), anyString(), any())).willReturn(true);
        given(voucherUsageRepository.existsByVoucherIdAndUserId(50L, 1L)).willReturn(false);
        given(voucherRepository.save(any())).willReturn(voucher);

        // bookingRepository.save() assigns the real DB id
        Booking persisted = persistedBooking(555L, new BigDecimal("800000"));
        given(bookingRepository.save(any(Booking.class))).willReturn(persisted);
        given(ticketCategoryRepository.save(any())).willReturn(mockCategoryVip);
        given(bookingRepository.countRecentBookingsByUser(eq(1L), any())).willReturn(1L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"id\":555}");

        CreateBookingRequest request = new CreateBookingRequest(
                "VIP200K", List.of(new BookingItemRequest(100L, 2)));

        // Act
        bookingService.createBooking(request, 1L, IDEM_KEY);

        // Assert — capture what voucherUsageRepository.save() received
        ArgumentCaptor<VoucherUsage> usageCaptor = ArgumentCaptor.forClass(VoucherUsage.class);
        verify(voucherUsageRepository, times(1)).save(usageCaptor.capture());

        VoucherUsage captured = usageCaptor.getValue();
        assertThat(captured.getBooking())
                .as("VoucherUsage must reference a non-null Booking object")
                .isNotNull();
        assertThat(captured.getBooking().getId())
                .as("VoucherUsage.booking_id must be set to the persisted booking's id (not null). "
                  + "A null here means bookingRepository.save() was not called before voucherUsageRepository.save().")
                .isNotNull()
                .isEqualTo(555L);
    }

    // =========================================================================
    // Test 16 — Duplicate idempotency key arrives at DB (Redis TTL just expired)
    //
    // Redis cache missed (TTL expired) but the DB UNIQUE index on idempotency_key
    // catches the duplicate.  The service must:
    //   1. Return BOOKING_ALREADY_EXISTS (not a 500)
    //   2. Roll back the Redis decrement (the booking won't be created this time)
    // =========================================================================

    @Test
    void createBooking_duplicateIdempotencyKeyAtDb_rollbacksRedisAndThrowsBookingAlreadyExists() {
        // Arrange — Redis misses (TTL expired), DB hits UNIQUE constraint
        stubSingleCategoryBase(48L);
        given(bookingRepository.save(any(Booking.class)))
                .willThrow(new DataIntegrityViolationException(
                        "Duplicate entry for key 'idx_bookings_idempotency_key'"));

        CreateBookingRequest request = new CreateBookingRequest(
                null, List.of(new BookingItemRequest(100L, 2)));

        // Act & Assert
        assertThatThrownBy(() -> bookingService.createBooking(request, 1L, IDEM_KEY))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BOOKING_ALREADY_EXISTS);

        // Redis must be restored — this request didn't actually create a booking
        verify(valueOperations, times(1)).increment(eq(INV_KEY_100), eq(2L));
    }
}
