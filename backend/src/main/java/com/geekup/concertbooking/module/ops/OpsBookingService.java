package com.geekup.concertbooking.module.ops;

import com.geekup.concertbooking.config.AppProperties;
import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.entity.BookingItem;
import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.entity.TicketCategory;
import com.geekup.concertbooking.module.booking.BookingItemRepository;
import com.geekup.concertbooking.module.booking.BookingRepository;
import com.geekup.concertbooking.module.booking.dto.BookingItemResponse;
import com.geekup.concertbooking.module.concert.ConcertRepository;
import com.geekup.concertbooking.module.concert.TicketCategoryRepository;
import com.geekup.concertbooking.module.ops.dto.OpsBookingDTO.*;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpsBookingService {

    private final BookingRepository        bookingRepository;
    private final BookingItemRepository    bookingItemRepository;
    private final ConcertRepository        concertRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final AppProperties            appProperties;

    // Inject trực tiếp RedisTemplate để rollback inventory
    private final org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;

    // =========================================================================
    //  LIST BOOKINGS với filter linh hoạt
    // =========================================================================

    /**
     * Danh sách booking với filter tùy chọn.
     * Tất cả params đều nullable — nếu null thì không áp filter đó.
     * Default sort: createdAt DESC (truyền qua Pageable từ controller).
     *
     * @param concertId    filter theo concert (nullable)
     * @param status       filter theo status (nullable)
     * @param isSuspicious filter suspicious flag (nullable)
     * @param pageable     pagination + sort
     */
    @Transactional(readOnly = true)
    public Page<BookingListResponse> listBookings(
            Long concertId, BookingStatus status, Boolean isSuspicious,
            Pageable pageable) {

        Specification<Booking> spec = Specification
            .where(BookingSpecification.hasConcertId(concertId))
            .and(BookingSpecification.hasStatus(status))
            .and(BookingSpecification.isSuspicious(isSuspicious));

        return bookingRepository.findAll(spec, pageable)
            .map(this::toListResponse);
    }

    // =========================================================================
    //  GET BOOKING DETAIL
    // =========================================================================

    /**
     * Chi tiết 1 booking — ops có quyền xem tất cả, không check ownership.
     * Load items từ BookingItemRepository để tránh LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public BookingDetailResponse getBookingDetail(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        List<BookingItem> items = bookingItemRepository.findByBookingId(bookingId);
        return toDetailResponse(booking, items);
    }

    // =========================================================================
    //  MANUAL UPDATE STATUS
    // =========================================================================

    /**
     * Ops update status thủ công với validate transition.
     *
     * Các transition được phép:
     *   - Bất kỳ (trừ COMPLETED, EXPIRED) → CANCELLED  (bắt buộc có reason)
     *   - PAID → COMPLETED
     *
     * Các transition bị chặn:
     *   - COMPLETED → bất kỳ (terminal state)
     *   - EXPIRED → bất kỳ (terminal state)
     *
     * Side effects khi CANCELLED:
     *   - Set cancelReason, cancelledAt
     *   - Rollback Redis inventory nếu status cũ là PENDING / WAITING_PAYMENT
     *   - Rollback DB availableQuantity
     */
    @Transactional(rollbackFor = Exception.class)
    public BookingDetailResponse manualUpdateStatus(Long bookingId, UpdateBookingStatusRequest request) {
        String operator = resolveOperatorName();

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        BookingStatus oldStatus  = booking.getStatus();
        BookingStatus newStatus  = request.status();

        log.info("Ops manual status update: booking {} from {} to {} by operator {}",
            bookingId, oldStatus, newStatus, operator);

        // --- Validate transition ---
        validateTransition(oldStatus, newStatus, request.reason());

        // --- Apply transition ---
        booking.setStatus(newStatus);

        if (newStatus == BookingStatus.CANCELLED) {
            booking.setCancelledAt(LocalDateTime.now());
            booking.setCancelReason(
                request.reason() != null ? request.reason()
                    : "Cancelled by ops operator: " + operator
            );

            // Rollback inventory chỉ khi booking đang giữ vé (PENDING / WAITING_PAYMENT)
            if (oldStatus == BookingStatus.PENDING || oldStatus == BookingStatus.WAITING_PAYMENT) {
                List<BookingItem> items = bookingItemRepository.findByBookingId(bookingId);
                restoreInventory(items);
                log.info("Inventory restored for ops-cancelled booking {}", bookingId);
            }
        }

        if (newStatus == BookingStatus.COMPLETED) {
            // paidAt đã được set khi PAID — giữ nguyên, không override
            log.info("Booking {} marked COMPLETED by ops operator {}", bookingId, operator);
        }

        bookingRepository.save(booking);

        List<BookingItem> items = bookingItemRepository.findByBookingId(bookingId);
        return toDetailResponse(booking, items);
    }

    // =========================================================================
    //  SUSPICIOUS BOOKINGS
    // =========================================================================

    /**
     * Tất cả booking bị đánh dấu isSuspicious = true, phân trang.
     */
    @Transactional(readOnly = true)
    public Page<BookingListResponse> getSuspiciousBookings(Pageable pageable) {
        return bookingRepository.findByIsSuspiciousTrue(pageable)
            .map(this::toListResponse);
    }

    // =========================================================================
    //  BOOKING STATS
    // =========================================================================

    /**
     * Tổng hợp số liệu booking theo concert cho ops dashboard.
     * Dùng các @Query đã thêm vào BookingRepository.
     *
     * NOTE: concertId bắt buộc — ops query stats luôn theo concert cụ thể.
     */
    @Transactional(readOnly = true)
    public BookingStatsResponse getBookingStats(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
            .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));

        long totalBookings       = bookingRepository.countByConcertId(concertId);
        long pendingCount        = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.PENDING);
        long waitingPaymentCount = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.WAITING_PAYMENT);
        long paidCount           = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.PAID);
        long completedCount      = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.COMPLETED);
        long cancelledCount      = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.CANCELLED);
        long expiredCount        = bookingRepository.countByConcertIdAndStatus(concertId, BookingStatus.EXPIRED);

        BigDecimal totalRevenue  = bookingRepository.sumFinalAmountByConcertIdAndStatusIn(
            concertId, List.of(BookingStatus.PAID, BookingStatus.COMPLETED)
        );
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;

        return new BookingStatsResponse(
            concertId,
            concert.getName(),
            totalBookings,
            pendingCount,
            waitingPaymentCount,
            paidCount,
            completedCount,
            cancelledCount,
            expiredCount,
            totalRevenue
        );
    }

    // =========================================================================
    //  PRIVATE HELPERS
    // =========================================================================

    /**
     * Validate transition hợp lệ theo business rule.
     * Throw BOOKING_INVALID_STATUS nếu vi phạm.
     */
    private void validateTransition(BookingStatus from, BookingStatus to, String reason) {
        // Terminal states — không chuyển đi đâu được nữa
        if (from == BookingStatus.COMPLETED) {
            throw new AppException(ErrorCode.BOOKING_INVALID_STATUS,
                "Không thể thay đổi booking đã COMPLETED");
        }
        if (from == BookingStatus.EXPIRED) {
            throw new AppException(ErrorCode.BOOKING_INVALID_STATUS,
                "Không thể thay đổi booking đã EXPIRED");
        }

        // Chuyển sang COMPLETED chỉ khi đang là PAID
        if (to == BookingStatus.COMPLETED && from != BookingStatus.PAID) {
            throw new AppException(ErrorCode.BOOKING_INVALID_STATUS,
                "Chỉ có thể chuyển sang COMPLETED khi booking đang ở trạng thái PAID. "
                + "Trạng thái hiện tại: " + from);
        }

        // Khi cancel, reason nên được cung cấp (warning log, không throw)
        if (to == BookingStatus.CANCELLED && (reason == null || reason.isBlank())) {
            log.warn("Ops cancelling booking without providing a reason");
        }

        // Không cho phép chuyển sang PENDING / WAITING_PAYMENT / EXPIRED thủ công
        if (to == BookingStatus.PENDING || to == BookingStatus.WAITING_PAYMENT
                || to == BookingStatus.EXPIRED) {
            throw new AppException(ErrorCode.BOOKING_INVALID_STATUS,
                "Ops không được phép chuyển sang trạng thái: " + to);
        }
    }

    /**
     * Restore inventory (Redis + DB) cho tất cả items của booking bị cancel.
     * Giống logic restoreInventory() trong BookingService — fire-and-forget
     * với warn log nếu Redis fail, error log nếu DB fail.
     */
    private void restoreInventory(List<BookingItem> items) {
        String inventoryPrefix = appProperties.getRedis().getInventoryKeyPrefix();

        for (BookingItem item : items) {
            Long categoryId = item.getTicketCategory().getId();
            int  qty        = item.getQuantity();

            // Redis rollback — tăng lại số lượng
            String redisKey = inventoryPrefix + categoryId;
            try {
                redisTemplate.opsForValue().increment(redisKey, qty);
                log.debug("Ops inventory rollback Redis: +{} for key {}", qty, redisKey);
            } catch (Exception e) {
                log.warn("Ops Redis inventory restore failed for key {}: {}", redisKey, e.getMessage());
            }

            // DB rollback — tăng availableQuantity
            try {
                TicketCategory category = ticketCategoryRepository.findById(categoryId).orElse(null);
                if (category != null) {
                    category.setAvailableQuantity(category.getAvailableQuantity() + qty);
                    ticketCategoryRepository.save(category);
                    log.debug("Ops inventory rollback DB: +{} for category {}", qty, categoryId);
                }
            } catch (Exception e) {
                log.error("Ops DB inventory restore failed for category {}: {}", categoryId, e.getMessage(), e);
            }
        }
    }

    /**
     * Lấy username của operator đang thao tác từ SecurityContext.
     */
    private String resolveOperatorName() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // =========================================================================
    //  MAPPERS
    // =========================================================================

    private BookingListResponse toListResponse(Booking b) {
        return new BookingListResponse(
            b.getId(),
            b.getUser().getId(),
            b.getUser().getEmail(),
            b.getConcert().getId(),
            b.getConcert().getName(),
            b.getStatus(),
            b.getTotalAmount(),
            b.getFinalAmount(),
            b.getVoucher() != null ? b.getVoucher().getCode() : null,
            b.getIsSuspicious(),
            b.getCreatedAt(),
            b.getExpiresAt()
        );
    }

    private BookingDetailResponse toDetailResponse(Booking b, List<BookingItem> items) {
        List<BookingItemResponse> itemResponses = items.stream()
            .map(BookingItemResponse::from)
            .toList();

        return new BookingDetailResponse(
            b.getId(),
            b.getUser().getId(),
            b.getUser().getEmail(),
            b.getConcert().getId(),
            b.getConcert().getName(),
            b.getStatus(),
            b.getTotalAmount(),
            b.getFinalAmount(),
            b.getDiscountAmount(),
            b.getVoucher() != null ? b.getVoucher().getCode() : null,
            b.getIsSuspicious(),
            itemResponses,
            b.getCreatedAt(),
            b.getUpdatedAt(),
            b.getExpiresAt(),
            b.getPaidAt(),
            b.getCancelledAt(),
            b.getCancelReason()
        );
    }
}
