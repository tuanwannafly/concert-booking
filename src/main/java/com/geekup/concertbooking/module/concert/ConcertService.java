package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.config.AppProperties;
import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.entity.TicketCategory;
import com.geekup.concertbooking.module.concert.dto.*;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConcertService {

    private final ConcertRepository concertRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final RedisTemplate<String, String> stringRedisTemplate;
    private final AppProperties appProperties;

    // ═══════════════════════════════════════════════════════════════════════════
    // CUSTOMER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Danh sách concert đang PUBLISHED — public, có phân trang.
     */
    @Transactional(readOnly = true)
    public Page<ConcertResponse> listPublishedConcerts(Pageable pageable) {
        return concertRepository
                .findByStatus(ConcertStatus.PUBLISHED, pageable)
                .map(ConcertResponse::from);
    }

    /**
     * Chi tiết một concert PUBLISHED kèm toàn bộ loại vé.
     * availableQuantity trong từng loại vé được lấy từ Redis (real-time).
     */
    @Transactional(readOnly = true)
    public ConcertDetailResponse getConcertDetail(Long concertId) {
        Concert concert = concertRepository.findByIdAndStatus(concertId, ConcertStatus.PUBLISHED)
                .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));

        List<TicketCategoryResponse> categories = resolveCategories(concert);
        return ConcertDetailResponse.from(concert, categories);
    }

    /**
     * Danh sách loại vé của một concert PUBLISHED.
     * Chỉ trả về khi concert đang PUBLISHED.
     */
    @Transactional(readOnly = true)
    public List<TicketCategoryResponse> getTicketCategories(Long concertId) {
        Concert concert = concertRepository.findByIdAndStatus(concertId, ConcertStatus.PUBLISHED)
                .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));

        return resolveCategories(concert);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPS METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tạo concert mới với status=DRAFT.
     * Chỉ OPERATOR/ADMIN mới gọi được (enforce tại Controller).
     */
    @Transactional
    public ConcertResponse createConcert(CreateConcertRequest request) {
        Concert concert = Concert.builder()
                .name(request.getName())
                .venue(request.getVenue())
                .eventDate(request.getEventDate())
                .description(request.getDescription())
                .bannerUrl(request.getBannerUrl())
                .status(ConcertStatus.DRAFT)
                .build();

        return ConcertResponse.from(concertRepository.save(concert));
    }

    /**
     * Partial update concert — chỉ cho phép khi đang DRAFT.
     * Chỉ update field nào client gửi lên (không null).
     */
    @Transactional
    public ConcertResponse updateConcert(Long id, UpdateConcertRequest request) {
        Concert concert = findDraftConcertOrThrow(id);

        if (request.getName() != null)        concert.setName(request.getName());
        if (request.getVenue() != null)       concert.setVenue(request.getVenue());
        if (request.getEventDate() != null)   concert.setEventDate(request.getEventDate());
        if (request.getDescription() != null) concert.setDescription(request.getDescription());
        if (request.getBannerUrl() != null)   concert.setBannerUrl(request.getBannerUrl());

        return ConcertResponse.from(concertRepository.save(concert));
    }

    /**
     * Publish concert: DRAFT → PUBLISHED.
     *
     * Các bước thực hiện:
     *  1. Tìm concert, xác minh đang DRAFT
     *  2. Validate có ít nhất 1 ticket category
     *  3. Set status = PUBLISHED, publishedAt = now()
     *  4. Load inventory vào Redis: SET "inventory:ticket:{categoryId}" = availableQuantity
     *  5. Save và return ConcertDetailResponse
     */
    @Transactional
    public ConcertDetailResponse publishConcert(Long concertId) {
        // ── Bước 1: Tìm concert và xác minh đang DRAFT ────────────────────────
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));

        if (concert.getStatus() != ConcertStatus.DRAFT) {
            throw new AppException(ErrorCode.INVALID_CONCERT_STATUS,
                    "Chỉ có thể publish concert đang ở trạng thái DRAFT");
        }

        // ── Bước 2: Validate phải có ít nhất 1 ticket category ────────────────
        List<TicketCategory> categories = ticketCategoryRepository.findByConcertId(concertId);
        if (categories.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CONCERT_STATUS,
                    "Concert phải có ít nhất 1 loại vé trước khi publish");
        }

        // ── Bước 3: Chuyển trạng thái → PUBLISHED ─────────────────────────────
        concert.setStatus(ConcertStatus.PUBLISHED);
        concert.setPublishedAt(LocalDateTime.now());
        Concert savedConcert = concertRepository.save(concert);

        // ── Bước 4: Load inventory vào Redis ──────────────────────────────────
        // Mỗi category có 1 key riêng: "inventory:ticket:{categoryId}"
        // Đây là source of truth real-time cho việc check và deduct inventory khi booking.
        for (TicketCategory category : categories) {
            String redisKey = getRedisInventoryKey(category.getId());
            stringRedisTemplate.opsForValue().set(redisKey,
                    String.valueOf(category.getAvailableQuantity()));
        }

        log.info("Loaded inventory to Redis for concert {}: {} categories | keys: {}",
                concertId,
                categories.size(),
                categories.stream()
                        .map(c -> getRedisInventoryKey(c.getId()) + "=" + c.getAvailableQuantity())
                        .toList());

        // ── Bước 5: Build response với availableQty từ Redis (vừa set xong) ──
        List<TicketCategoryResponse> categoryResponses = categories.stream()
                .map(cat -> {
                    Long redisQty = getAvailableQtyFromRedis(cat.getId());
                    // Fallback về DB nếu Redis chưa kịp phản ánh (lý thuyết không xảy ra ở đây)
                    long qty = redisQty != null ? redisQty : cat.getAvailableQuantity().longValue();
                    return TicketCategoryResponse.from(cat, qty);
                })
                .toList();

        return ConcertDetailResponse.from(savedConcert, categoryResponses);
    }

    /**
     * Thêm loại vé vào concert — chỉ khi concert đang DRAFT.
     */
    @Transactional
    public TicketCategoryResponse addTicketCategory(Long concertId, AddTicketCategoryRequest request) {
        Concert concert = findDraftConcertOrThrow(concertId);

        TicketCategory category = TicketCategory.builder()
                .concert(concert)
                .name(request.getName())
                .price(request.getPrice())
                .totalQuantity(request.getTotalQuantity())
                .availableQuantity(request.getTotalQuantity()) // ban đầu available = total
                .maxPerBooking(request.getMaxPerBooking() != null ? request.getMaxPerBooking() : 4)
                .build();

        TicketCategory saved = ticketCategoryRepository.save(category);
        return TicketCategoryResponse.fromDb(saved);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REDIS HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Trả về Redis key cho inventory của một ticket category.
     * Format: "inventory:ticket:{categoryId}"
     */
    public String getRedisInventoryKey(Long categoryId) {
        return appProperties.getRedis().getInventoryKeyPrefix() + categoryId;
    }

    /**
     * Lấy số vé khả dụng từ Redis.
     * Trả về null nếu key không tồn tại (chưa publish hoặc Redis bị flush).
     */
    public Long getAvailableQtyFromRedis(Long categoryId) {
        String value = stringRedisTemplate.opsForValue().get(getRedisInventoryKey(categoryId));
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid inventory value in Redis for category {}: '{}'", categoryId, value);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve availableQuantity cho từng category:
     * - PUBLISHED → Redis (real-time), fallback DB.
     * - DRAFT/ENDED → DB.
     */
    private List<TicketCategoryResponse> resolveCategories(Concert concert) {
        List<TicketCategory> categories = ticketCategoryRepository.findByConcertId(concert.getId());
        boolean isPublished = concert.getStatus() == ConcertStatus.PUBLISHED;

        return categories.stream()
                .map(cat -> {
                    if (isPublished) {
                        Long redisQty = getAvailableQtyFromRedis(cat.getId());
                        long qty = redisQty != null
                                ? redisQty
                                : cat.getAvailableQuantity().longValue(); // fallback
                        return TicketCategoryResponse.from(cat, qty);
                    } else {
                        return TicketCategoryResponse.fromDb(cat);
                    }
                })
                .toList();
    }

    /**
     * Tìm concert theo id, throw nếu không tồn tại hoặc không phải DRAFT.
     */
    private Concert findDraftConcertOrThrow(Long id) {
        Concert concert = concertRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));
        if (concert.getStatus() != ConcertStatus.DRAFT) {
            throw new AppException(ErrorCode.INVALID_CONCERT_STATUS,
                    "Thao tác này chỉ được phép khi concert đang ở trạng thái DRAFT");
        }
        return concert;
    }
}
