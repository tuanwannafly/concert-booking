package com.geekup.concertbooking.module.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository       bookingRepository;
    private final BookingItemRepository   bookingItemRepository;
    private final UserRepository          userRepository;
    private final ConcertRepository       concertRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final VoucherRepository       voucherRepository;
    private final VoucherUsageRepository  voucherUsageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final AppProperties           appProperties;
    private final ObjectMapper            objectMapper;

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Tạo booking mới với đầy đủ idempotency, inventory check, voucher, suspicious detection.
     *
     * @param request         payload từ client
     * @param userId          ID user đã authenticated (lấy từ SecurityContext ở controller)
     * @param idempotencyKey  giá trị header X-Idempotency-Key
     */
    @Transactional(rollbackFor = Exception.class)
    public BookingResponse createBooking(CreateBookingRequest request, Long userId, String idempotencyKey) {
        log.info("Creating booking for user {} idempotencyKey {}", userId, idempotencyKey);

        // ---------------------------------------------------------------
        // STEP 1 — Idempotency check
        // Nếu request này đã được xử lý thành công → trả về cached result,
        // không xử lý lại để tránh double-charge / double-deduct inventory.
        // ---------------------------------------------------------------
        String idemRedisKey = appProperties.getRedis().getIdempotencyKeyPrefix() + idempotencyKey;
        try {
            String cached = redisTemplate.opsForValue().get(idemRedisKey);
            if (cached != null) {
                log.info("Idempotency hit for key {}, returning cached response", idempotencyKey);
                return objectMapper.readValue(cached, BookingResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis idempotency read failed for key {}: {}", idempotencyKey, e.getMessage());
            // Redis failure không block flow — tiếp tục xử lý bình thường.
            // Nếu DB có UNIQUE constraint, duplicate sẽ bị chặn ở STEP 6.
        }

        // ---------------------------------------------------------------
        // STEP 2 — Validate inputs
        // ---------------------------------------------------------------
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        // Lấy concert đầu tiên từ ticketCategoryId để xác định concertId.
        // Tất cả item phải thuộc cùng 1 concert — validate từng category bên dưới.
        Long firstCategoryId = request.items().get(0).ticketCategoryId();
        TicketCategory firstCategory = ticketCategoryRepository.findById(firstCategoryId)
            .orElseThrow(() -> new AppException(ErrorCode.TICKET_CATEGORY_NOT_FOUND));

        Concert concert = firstCategory.getConcert();
        if (concert.getStatus() != ConcertStatus.PUBLISHED) {
            throw new AppException(ErrorCode.CONCERT_NOT_PUBLISHED);
        }

        log.debug("Creating booking for user {} concert {}", userId, concert.getId());

        // Validate từng item: load category, kiểm tra maxPerBooking, kiểm tra cùng concert
        List<TicketCategory> categories = new ArrayList<>();
        for (BookingItemRequest itemReq : request.items()) {
            TicketCategory category = ticketCategoryRepository.findById(itemReq.ticketCategoryId())
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_CATEGORY_NOT_FOUND));

            // Cross-concert guard: tất cả item phải thuộc cùng concert với item đầu tiên
            if (!category.getConcert().getId().equals(concert.getId())) {
                throw new AppException(ErrorCode.BOOKING_CROSS_CONCERT,
                    "Vé '" + category.getName() + "' thuộc concert khác — không được mix vé nhiều concert trong 1 đơn");
            }

            if (itemReq.quantity() > category.getMaxPerBooking()) {
                throw new AppException(ErrorCode.TICKET_QUANTITY_EXCEEDED,
                    "Loại vé '" + category.getName() + "' chỉ cho phép tối đa "
                    + category.getMaxPerBooking() + " vé mỗi lần đặt");
            }
            categories.add(category);
        }

        // ---------------------------------------------------------------
        // STEP 3 — Calculate totalAmount
        // ---------------------------------------------------------------
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < request.items().size(); i++) {
            BookingItemRequest itemReq = request.items().get(i);
            TicketCategory category = categories.get(i);
            BigDecimal subtotal = category.getPrice().multiply(BigDecimal.valueOf(itemReq.quantity()));
            totalAmount = totalAmount.add(subtotal);
        }

        // ---------------------------------------------------------------
        // STEP 4 — Validate và apply voucher (nếu có)
        // ---------------------------------------------------------------
        Voucher appliedVoucher = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal finalAmount = totalAmount;
        String acquiredVoucherLockKey = null;

        if (request.voucherCode() != null && !request.voucherCode().isBlank()) {
            VoucherResult voucherResult = validateAndComputeDiscount(request.voucherCode(), userId, totalAmount);
            appliedVoucher = voucherResult.voucher();
            discountAmount = voucherResult.discountAmount();
            acquiredVoucherLockKey = voucherResult.lockKey();
            finalAmount = totalAmount.subtract(discountAmount);
            log.debug("Voucher {} applied, discount={}", request.voucherCode(), discountAmount);
        }

        // ---------------------------------------------------------------
        // STEP 5 — Redis inventory check (DECRBY — atomic)
        // Đây là lớp fast-check trước khi chạm vào DB.
        // Mỗi DECRBY là atomic → an toàn với concurrent request.
        // Nếu bất kỳ category nào hết hàng → rollback tất cả đã deduct.
        // ---------------------------------------------------------------
        List<Long> deductedCategoryIds   = new ArrayList<>();
        List<Integer> deductedQuantities = new ArrayList<>();

        for (int i = 0; i < request.items().size(); i++) {
            BookingItemRequest itemReq  = request.items().get(i);
            TicketCategory category     = categories.get(i);
            String inventoryKey = appProperties.getRedis().getInventoryKeyPrefix() + itemReq.ticketCategoryId();

            try {
                Long remaining = redisTemplate.opsForValue().decrement(inventoryKey, itemReq.quantity());

                if (remaining != null && remaining < 0) {
                    // Hết hàng — rollback item này
                    try {
                        redisTemplate.opsForValue().increment(inventoryKey, itemReq.quantity());
                    } catch (Exception ex) {
                        log.warn("Redis rollback failed for key {}: {}", inventoryKey, ex.getMessage());
                    }
                    // Rollback tất cả item TRƯỚC đó trong loop
                    rollbackRedisInventory(deductedCategoryIds, deductedQuantities);

                    throw new AppException(ErrorCode.TICKET_SOLD_OUT,
                        "Vé '" + category.getName() + "' đã hết, vui lòng chọn loại vé khác");
                }

                // Ghi nhận đã deduct thành công để rollback nếu cần
                deductedCategoryIds.add(itemReq.ticketCategoryId());
                deductedQuantities.add(itemReq.quantity());

            } catch (AppException e) {
                throw e; // re-throw để không bị catch bên dưới nuốt mất
            } catch (Exception e) {
                log.warn("Redis inventory decrement failed for key {}: {}", inventoryKey, e.getMessage());
                // Redis không available → fallback: dựa vào DB optimistic lock ở STEP 6.
                // Vẫn ghi nhận để rollback nếu cần.
                deductedCategoryIds.add(itemReq.ticketCategoryId());
                deductedQuantities.add(itemReq.quantity());
            }
        }

        // ---------------------------------------------------------------
        // STEP 6 — Persist Booking trong @Transactional
        // ---------------------------------------------------------------
        Booking savedBooking;
        final Voucher finalAppliedVoucher = appliedVoucher;
        final BigDecimal finalDiscountAmount = discountAmount;

        try {
            LocalDateTime now = LocalDateTime.now();

            // Tạo Booking entity
            Booking booking = Booking.builder()
                .user(user)
                .concert(concert)
                .status(BookingStatus.PENDING)
                .totalAmount(totalAmount)
                .finalAmount(finalAmount)
                .voucher(finalAppliedVoucher)
                .discountAmount(finalDiscountAmount.compareTo(BigDecimal.ZERO) > 0 ? finalDiscountAmount : null)
                .idempotencyKey(idempotencyKey)
                .expiresAt(now.plusMinutes(appProperties.getBooking().getExpirationMinutes()))
                .isSuspicious(false)
                .build();

            // Tạo BookingItems — snapshot giá tại thời điểm booking
            List<BookingItem> bookingItems = new ArrayList<>();
            for (int i = 0; i < request.items().size(); i++) {
                BookingItemRequest itemReq = request.items().get(i);
                TicketCategory category   = categories.get(i);

                BookingItem bookingItem = BookingItem.builder()
                    .booking(booking)
                    .ticketCategory(category)
                    .quantity(itemReq.quantity())
                    .unitPrice(category.getPrice())  // snapshot giá hiện tại
                    .subtotal(category.getPrice().multiply(BigDecimal.valueOf(itemReq.quantity())))
                    .build();
                bookingItems.add(bookingItem);
            }
            booking.getItems().addAll(bookingItems);

            // Save booking trước — CascadeType.ALL tự động persist BookingItems cùng lúc.
            // Booking phải có ID (được DB assign) trước khi tạo VoucherUsage,
            // vì VoucherUsage.booking_id là FK → bookings.id.
            // Save tại đây để tránh TransientPropertyValueException khi voucherUsageRepository.save().
            savedBooking = bookingRepository.save(booking);

            // Apply voucher: save VoucherUsage và tăng usedCount
            // Phải thực hiện SAU khi booking đã được persist và có ID.
            if (finalAppliedVoucher != null) {
                VoucherUsage usage = VoucherUsage.builder()
                    .voucher(finalAppliedVoucher)
                    .user(user)
                    .booking(savedBooking)   // dùng savedBooking (đã có ID), không dùng booking
                    .build();
                voucherUsageRepository.save(usage);

                finalAppliedVoucher.setUsedCount(finalAppliedVoucher.getUsedCount() + 1);
                voucherRepository.save(finalAppliedVoucher);
            }

            // Release distributed voucher lock ngay sau khi DB commit thành công.
            // DB UNIQUE(voucher_id, user_id) đảm bảo correctness; lock chỉ là UX guard.
            // Nếu giữ lock đến hết TTL 30s, user retry hợp lệ sẽ nhận "đang xử lý" sai.
            if (acquiredVoucherLockKey != null) {
                try {
                    redisTemplate.delete(acquiredVoucherLockKey);
                    log.debug("Voucher lock released: {}", acquiredVoucherLockKey);
                } catch (Exception e) {
                    log.warn("Failed to release voucher lock {}: {}", acquiredVoucherLockKey, e.getMessage());
                }
            }

            // Update TicketCategory.availableQuantity trong DB (optimistic lock safety net)
            for (int i = 0; i < request.items().size(); i++) {
                BookingItemRequest itemReq = request.items().get(i);
                TicketCategory category   = categories.get(i);
                updateCategoryQuantityWithRetry(category, itemReq.quantity());
            }

            log.info("Booking {} created successfully for user {} concert {}",
                savedBooking.getId(), userId, concert.getId());

        } catch (DataIntegrityViolationException e) {
            // Duplicate idempotency key → booking đã tồn tại nhưng không có trong Redis cache
            log.warn("Duplicate idempotency key {} — booking already exists in DB", idempotencyKey);
            rollbackRedisInventory(deductedCategoryIds, deductedQuantities);
            throw new AppException(ErrorCode.BOOKING_ALREADY_EXISTS);

        } catch (AppException e) {
            rollbackRedisInventory(deductedCategoryIds, deductedQuantities);
            throw e;

        } catch (Exception e) {
            log.error("Unexpected error creating booking for user {}: {}", userId, e.getMessage(), e);
            rollbackRedisInventory(deductedCategoryIds, deductedQuantities);
            throw e;
        }

        // ---------------------------------------------------------------
        // STEP 7 — Suspicious detection
        // > 3 booking trong vòng 5 phút gần nhất → đánh dấu suspicious
        // Không block flow, chỉ flag để Ops xem xét.
        // ---------------------------------------------------------------
        try {
            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
            long recentCount = bookingRepository.countRecentBookingsByUser(userId, fiveMinutesAgo);
            if (recentCount > 3) {
                log.warn("Suspicious booking detected for user {} — {} bookings in last 5 minutes",
                    userId, recentCount);
                savedBooking.setIsSuspicious(true);
                savedBooking = bookingRepository.save(savedBooking);
            }
        } catch (Exception e) {
            log.warn("Suspicious detection failed for user {}: {}", userId, e.getMessage());
        }

        // ---------------------------------------------------------------
        // STEP 8 — Cache idempotency response vào Redis (TTL 24h)
        // ---------------------------------------------------------------
        BookingResponse response = BookingResponse.from(savedBooking);
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(
                idemRedisKey,
                json,
                Duration.ofHours(appProperties.getRedis().getIdempotencyTtlHours())
            );
            log.debug("Idempotency response cached for key {}", idempotencyKey);
        } catch (Exception e) {
            log.warn("Redis idempotency write failed for key {}: {}", idempotencyKey, e.getMessage());
            // Không throw — chỉ mất idempotency cache, không ảnh hưởng nghiệp vụ
        }

        // ---------------------------------------------------------------
        // STEP 9 — Return
        // ---------------------------------------------------------------
        return response;
    }

    /**
     * Giả thanh toán — chuyển booking sang trạng thái PAID.
     * Phase 4 sẽ tích hợp payment gateway thật.
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentResponse pay(Long bookingId, Long userId) {
        log.info("Processing payment for booking {} by user {}", bookingId, userId);

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        // Validate ownership
        if (!booking.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.BOOKING_NOT_OWNED);
        }

        // Validate status — chỉ cho phép pay khi PENDING hoặc WAITING_PAYMENT
        BookingStatus status = booking.getStatus();
        if (status == BookingStatus.CANCELLED || status == BookingStatus.EXPIRED) {
            throw new AppException(ErrorCode.BOOKING_CANNOT_CANCEL,
                "Không thể thanh toán booking đã bị huỷ hoặc hết hạn");
        }
        if (status != BookingStatus.PENDING && status != BookingStatus.WAITING_PAYMENT) {
            throw new AppException(ErrorCode.BOOKING_INVALID_STATUS,
                "Booking ở trạng thái " + status + " không thể thanh toán");
        }

        // Validate chưa hết hạn
        if (LocalDateTime.now().isAfter(booking.getExpiresAt())) {
            // Auto-expire ngay tại đây (scheduler sẽ catch phần còn lại)
            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);
            throw new AppException(ErrorCode.BOOKING_EXPIRED);
        }

        // Mock payment: chuyển thẳng sang PAID.
        // Trong production, flow sẽ là:
        //   1. POST /pay          → WAITING_PAYMENT  (initiate payment)
        //   2. Payment gateway webhook → PAID         (confirm payment)
        // Vì đây là mock endpoint không có gateway thật, gộp 2 bước thành 1
        // để Postman collection dễ test và tránh nhầm lẫn.
        booking.setStatus(BookingStatus.PAID);
        booking.setPaidAt(LocalDateTime.now());
        bookingRepository.save(booking);

        log.info("Booking {} paid successfully by user {}", bookingId, userId);
        return PaymentResponse.from(booking);
    }

    /**
     * Huỷ booking — chỉ khi còn PENDING hoặc WAITING_PAYMENT.
     * Sẽ rollback inventory (Redis + DB).
     */
    @Transactional(rollbackFor = Exception.class)
    public BookingResponse cancelBooking(Long bookingId, Long userId) {
        log.info("Cancelling booking {} by user {}", bookingId, userId);

        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        if (!booking.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.BOOKING_NOT_OWNED);
        }

        BookingStatus status = booking.getStatus();
        if (status != BookingStatus.PENDING && status != BookingStatus.WAITING_PAYMENT) {
            throw new AppException(ErrorCode.BOOKING_CANNOT_CANCEL,
                "Chỉ có thể huỷ booking ở trạng thái PENDING hoặc WAITING_PAYMENT. " +
                "Trạng thái hiện tại: " + status);
        }

        // Đánh dấu cancelled
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        booking.setCancelReason("Cancelled by user");

        // Rollback inventory
        restoreInventory(booking.getItems());

        bookingRepository.save(booking);
        log.info("Booking {} cancelled, inventory restored", bookingId);

        return BookingResponse.from(booking);
    }

    /**
     * Lịch sử booking của user hiện tại, phân trang.
     */
    @Transactional(readOnly = true)
    public Page<BookingResponse> getMyBookings(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable)
            .map(BookingResponse::from);
    }

    /**
     * Chi tiết 1 booking — validate ownership.
     */
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));

        if (!booking.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.BOOKING_NOT_OWNED);
        }

        return BookingResponse.from(booking);
    }

    // =========================================================================
    //  PACKAGE-PRIVATE (dùng bởi BookingExpiryScheduler)
    // =========================================================================

    /**
     * Expire một booking: đặt status EXPIRED và restore inventory.
     * Được gọi bởi BookingExpiryScheduler trong transaction riêng.
     */
    @Transactional(rollbackFor = Exception.class)
    public void expireBooking(Booking booking) {
        booking.setStatus(BookingStatus.EXPIRED);
        restoreInventory(booking.getItems());
        bookingRepository.save(booking);
    }

    // =========================================================================
    //  PRIVATE HELPERS
    // =========================================================================

    /**
     * Validate voucher và tính discount amount.
     *
     * @return VoucherResult chứa (voucher entity, discountAmount)
     */
    private VoucherResult validateAndComputeDiscount(String voucherCode, Long userId, BigDecimal totalAmount) {

        // 1. Tìm voucher active
        Voucher voucher = voucherRepository.findByCodeAndIsActiveTrue(voucherCode)
            .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        // 2. Double-check isActive (findByCodeAndIsActiveTrue đã lọc, nhưng explicit check rõ hơn)
        if (!voucher.getIsActive()) {
            throw new AppException(ErrorCode.VOUCHER_INACTIVE);
        }

        // 3. Kiểm tra thời hạn hiệu lực
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(voucher.getValidFrom()) || now.isAfter(voucher.getValidTo())) {
            throw new AppException(ErrorCode.VOUCHER_EXPIRED);
        }

        // 4. Kiểm tra số lượt dùng còn lại
        if (voucher.getMaxUses() != null && voucher.getUsedCount() >= voucher.getMaxUses()) {
            throw new AppException(ErrorCode.VOUCHER_EXHAUSTED);
        }

        // 5. Kiểm tra giá trị đơn tối thiểu
        if (voucher.getMinOrderAmount() != null
            && totalAmount.compareTo(voucher.getMinOrderAmount()) < 0) {
            throw new AppException(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET,
                "Đơn hàng phải đạt tối thiểu " + voucher.getMinOrderAmount()
                + " để áp dụng voucher này");
        }

        // 6. Redis distributed lock — ngăn 2 request cùng claim voucher này của 1 user
        String lockKey = appProperties.getRedis().getVoucherLockKeyPrefix() + voucher.getId() + ":" + userId;
        Boolean lockAcquired = false;
        try {
            lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("Redis voucher lock failed for voucher {} user {}: {}", voucher.getId(), userId, e.getMessage());
            // Redis down → fallback sang DB check bên dưới
            lockAcquired = true;
        }

        if (Boolean.FALSE.equals(lockAcquired)) {
            // Ai đó đang trong quá trình dùng voucher này — reject để tránh race
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED,
                "Voucher đang được xử lý, vui lòng thử lại sau");
        }

        // 7. DB check — đảm bảo user chưa từng dùng voucher này
        if (voucherUsageRepository.existsByVoucherIdAndUserId(voucher.getId(), userId)) {
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED);
        }

        // 8. Tính discount
        BigDecimal discountAmount;
        if (voucher.getDiscountType() == DiscountType.PERCENT) {
            // Giảm theo phần trăm, không vượt quá tổng đơn
            BigDecimal percentDiscount = totalAmount
                .multiply(voucher.getDiscountValue())
                .divide(BigDecimal.valueOf(100));
            discountAmount = percentDiscount.min(totalAmount);
        } else {
            // FIXED — giảm cố định, không vượt quá tổng đơn
            discountAmount = voucher.getDiscountValue().min(totalAmount);
        }

        log.debug("Voucher {} validated: type={} discount={}", voucherCode, voucher.getDiscountType(), discountAmount);
        return new VoucherResult(voucher, discountAmount, lockKey);
    }

    /**
     * Rollback Redis inventory cho danh sách các (categoryId, quantity) đã deduct.
     * Fire-and-forget với log warning nếu fail — không throw.
     */
    private void rollbackRedisInventory(List<Long> categoryIds, List<Integer> quantities) {
        for (int i = 0; i < categoryIds.size(); i++) {
            String key = appProperties.getRedis().getInventoryKeyPrefix() + categoryIds.get(i);
            int qty = quantities.get(i);
            try {
                redisTemplate.opsForValue().increment(key, qty);
                log.debug("Redis inventory rollback: +{} for key {}", qty, key);
            } catch (Exception ex) {
                log.warn("Redis inventory rollback failed for key {}: {}", key, ex.getMessage());
            }
        }
    }

    /**
     * Restore inventory cho toàn bộ items của 1 booking (cancel / expire).
     * Cập nhật cả Redis lẫn DB.
     */
    private void restoreInventory(List<BookingItem> items) {
        for (BookingItem item : items) {
            Long categoryId = item.getTicketCategory().getId();
            int qty = item.getQuantity();

            // Redis rollback
            String redisKey = appProperties.getRedis().getInventoryKeyPrefix() + categoryId;
            try {
                redisTemplate.opsForValue().increment(redisKey, qty);
            } catch (Exception e) {
                log.warn("Redis inventory restore failed for key {}: {}", redisKey, e.getMessage());
            }

            // DB rollback — tăng availableQuantity
            try {
                TicketCategory category = ticketCategoryRepository.findById(categoryId)
                    .orElse(null);
                if (category != null) {
                    category.setAvailableQuantity(category.getAvailableQuantity() + qty);
                    ticketCategoryRepository.save(category);
                }
            } catch (Exception e) {
                log.error("DB inventory restore failed for category {}: {}", categoryId, e.getMessage(), e);
            }
        }
    }

    /**
     * Update availableQuantity với 1 lần retry nếu gặp OptimisticLockingFailureException.
     * Đây là safety net ngoài Redis DECRBY: đảm bảo DB luôn consistent.
     */
    private void updateCategoryQuantityWithRetry(TicketCategory category, int quantityToDeduct) {
        try {
            deductCategoryQuantity(category, quantityToDeduct);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict for category {}, retrying once...", category.getId());
            // Reload entity mới nhất rồi retry
            TicketCategory fresh = ticketCategoryRepository.findById(category.getId())
                .orElseThrow(() -> new AppException(ErrorCode.TICKET_CATEGORY_NOT_FOUND));
            deductCategoryQuantity(fresh, quantityToDeduct);
        }
    }

    private void deductCategoryQuantity(TicketCategory category, int quantityToDeduct) {
        int newQty = category.getAvailableQuantity() - quantityToDeduct;
        if (newQty < 0) {
            // Race condition: Redis cho qua nhưng DB lại hết → throw để rollback transaction
            throw new AppException(ErrorCode.INSUFFICIENT_INVENTORY,
                "Không đủ vé '" + category.getName() + "' trong hệ thống");
        }
        category.setAvailableQuantity(newQty);
        ticketCategoryRepository.save(category);
    }

    // =========================================================================
    //  INTERNAL RECORDS
    // =========================================================================

    /** Kết quả validate voucher: entity + discount amount đã tính + Redis lock key cần release. */
    private record VoucherResult(Voucher voucher, BigDecimal discountAmount, String lockKey) {}
}