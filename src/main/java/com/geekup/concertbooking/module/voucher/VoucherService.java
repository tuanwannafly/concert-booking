package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.Voucher;
import com.geekup.concertbooking.module.voucher.dto.*;
import com.geekup.concertbooking.shared.enums.DiscountType;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherRepository      voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Validate voucher và tính số tiền giảm — KHÔNG ghi DB.
     *
     * Dùng cho Customer endpoint POST /api/v1/vouchers/validate để preview
     * trước khi gọi createBooking. BookingService.applyVoucher() mới thực sự
     * increment usedCount và ghi VoucherUsage.
     *
     * Thứ tự validate:
     *   1. Code tồn tại
     *   2. isActive
     *   3. Thời hạn (validFrom / validTo)
     *   4. Còn lượt dùng (maxUses)
     *   5. Giá trị đơn tối thiểu (minOrderAmount)
     *   6. User chưa dùng voucher này
     *
     * @param code        mã voucher (case-sensitive)
     * @param userId      ID user đang đăng nhập
     * @param orderAmount tổng tiền đơn hàng (trước giảm giá)
     * @return ValidateVoucherResponse chứa discountAmount và finalAmount đã tính sẵn
     * @throws AppException nếu bất kỳ bước validate nào fail
     */
    @Transactional(readOnly = true)
    public ValidateVoucherResponse validateVoucher(String code, Long userId, BigDecimal orderAmount) {
        log.debug("Validating voucher code={} userId={} orderAmount={}", code, userId, orderAmount);

        LocalDateTime now = LocalDateTime.now();

        // Step 1 — Code tồn tại (tìm kể cả inactive để phân biệt NOT_FOUND vs INACTIVE)
        Voucher voucher = voucherRepository.findByCode(code)
            .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        // Step 2 — isActive
        if (!voucher.getIsActive()) {
            throw new AppException(ErrorCode.VOUCHER_INACTIVE);
        }

        // Step 3 — Thời hạn
        if (now.isBefore(voucher.getValidFrom()) || now.isAfter(voucher.getValidTo())) {
            throw new AppException(ErrorCode.VOUCHER_EXPIRED);
        }

        // Step 4 — Còn lượt dùng
        if (voucher.getMaxUses() != null && voucher.getUsedCount() >= voucher.getMaxUses()) {
            throw new AppException(ErrorCode.VOUCHER_EXHAUSTED);
        }

        // Step 5 — Giá trị đơn tối thiểu
        if (voucher.getMinOrderAmount() != null
                && orderAmount.compareTo(voucher.getMinOrderAmount()) < 0) {
            throw new AppException(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET);
        }

        // Step 6 — User chưa dùng voucher này
        if (voucherUsageRepository.existsByVoucherIdAndUserId(voucher.getId(), userId)) {
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED);
        }

        // Tính toán
        BigDecimal discountAmount = computeDiscount(voucher, orderAmount);
        BigDecimal finalAmount    = orderAmount.subtract(discountAmount);

        log.info("Voucher {} valid for userId={}: discount={}, final={}",
            code, userId, discountAmount, finalAmount);

        return new ValidateVoucherResponse(
            voucher.getCode(),
            voucher.getDiscountType(),
            voucher.getDiscountValue(),
            discountAmount,
            finalAmount
        );
    }

    /**
     * Tạo voucher mới — chỉ dành cho Admin (Ops endpoint).
     *
     * Voucher không thể chỉnh sửa sau khi tạo (xem assumptions.md).
     * Code phải unique trên toàn hệ thống, kể cả với voucher đã inactive.
     *
     * @throws AppException(VOUCHER_NOT_FOUND thay bằng conflict) nếu code đã tồn tại
     *         — thực tế trả về HTTP 409 via VOUCHER_EXHAUSTED... nhưng để rõ ràng,
     *         dùng thẳng AppException với message custom.
     */
    @Transactional
    public VoucherResponse createVoucher(CreateVoucherRequest request) {
        log.info("Admin creating voucher code={}", request.code());

        // Validate code unique (kể cả inactive)
        if (voucherRepository.existsByCode(request.code())) {
            throw new AppException(
                ErrorCode.VOUCHER_NOT_FOUND,
                "Mã voucher '" + request.code() + "' đã tồn tại trong hệ thống"
            );
        }

        // Validate validFrom < validTo
        if (!request.validFrom().isBefore(request.validTo())) {
            throw new AppException(
                ErrorCode.VOUCHER_EXPIRED,
                "validFrom phải trước validTo"
            );
        }

        // Validate PERCENT discount value trong khoảng 1–100
        if (request.discountType() == DiscountType.PERCENT) {
            BigDecimal val = request.discountValue();
            if (val.compareTo(BigDecimal.ONE) < 0 || val.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "discountValue cho PERCENT phải trong khoảng 1–100"
                );
            }
        }

        Voucher voucher = Voucher.builder()
            .code(request.code())
            .discountType(request.discountType())
            .discountValue(request.discountValue())
            .maxUses(request.maxUses())
            .usedCount(0)
            .minOrderAmount(request.minOrderAmount())
            .validFrom(request.validFrom())
            .validTo(request.validTo())
            .isActive(true)
            .build();

        Voucher saved = voucherRepository.save(voucher);
        log.info("Voucher created: id={} code={}", saved.getId(), saved.getCode());

        return VoucherResponse.from(saved);
    }

    /**
     * Liệt kê tất cả voucher với pagination — chỉ dành cho Admin (Ops endpoint).
     * Mỗi VoucherResponse có status tính toán động (ACTIVE / EXPIRED / INACTIVE / EXHAUSTED).
     */
    @Transactional(readOnly = true)
    public Page<VoucherResponse> listVouchers(Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        return voucherRepository.findAll(pageable)
            .map(v -> VoucherResponse.from(v, now));
    }

    // =========================================================================
    //  PRIVATE HELPERS
    // =========================================================================

    /**
     * Tính số tiền giảm thực tế dựa trên discountType.
     *
     * PERCENT: orderAmount × discountValue / 100, làm tròn xuống (FLOOR),
     *          không vượt quá orderAmount.
     * FIXED:   min(discountValue, orderAmount) — không giảm quá tổng tiền.
     *
     * Kết quả luôn có scale 0 (VND không có xu).
     */
    private BigDecimal computeDiscount(Voucher voucher, BigDecimal orderAmount) {
        return switch (voucher.getDiscountType()) {
            case PERCENT -> {
                BigDecimal raw = orderAmount
                    .multiply(voucher.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
                yield raw.min(orderAmount); // bảo vệ edge-case discount > 100%
            }
            case FIXED -> voucher.getDiscountValue().min(orderAmount);
        };
    }
}
