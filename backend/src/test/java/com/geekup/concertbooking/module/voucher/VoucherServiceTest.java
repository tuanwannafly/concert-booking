package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.Voucher;
import com.geekup.concertbooking.shared.enums.DiscountType;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

    @Mock private VoucherRepository voucherRepository;
    @Mock private VoucherUsageRepository voucherUsageRepository;

    @InjectMocks
    private VoucherService voucherService;

    // ── helper ───────────────────────────────────────────────────────────────

    private Voucher buildActiveVoucher(String code) {
        return Voucher.builder()
                .id(1L)
                .code(code)
                .discountType(DiscountType.PERCENT)
                .discountValue(new BigDecimal("10"))
                .maxUses(100)
                .usedCount(0)
                .minOrderAmount(new BigDecimal("200000"))
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .isActive(true)
                .build();
    }

    // =========================================================================
    // Test 8: validateVoucher_alreadyUsedByUser_throwsAppException
    // =========================================================================

    @Test
    void validateVoucher_alreadyUsedByUser_throwsAppException() {
        // Arrange — voucher is valid but this user has already used it before
        Voucher voucher = buildActiveVoucher("USED10");
        given(voucherRepository.findByCode("USED10")).willReturn(Optional.of(voucher));
        given(voucherUsageRepository.existsByVoucherIdAndUserId(1L, 42L)).willReturn(true);

        // Act & Assert
        assertThatThrownBy(() ->
                voucherService.validateVoucher("USED10", 42L, new BigDecimal("500000")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_ALREADY_USED);
    }

    // =========================================================================
    // Test 9: validateVoucher_expired_throwsAppException
    // =========================================================================

    @Test
    void validateVoucher_expired_throwsAppException() {
        // Arrange — voucher validTo is in the past (expired yesterday)
        Voucher expiredVoucher = Voucher.builder()
                .id(2L)
                .code("OLDVOUCHER")
                .discountType(DiscountType.FIXED)
                .discountValue(new BigDecimal("50000"))
                .maxUses(100)
                .usedCount(0)
                .minOrderAmount(null)
                .validFrom(LocalDateTime.now().minusDays(30))
                .validTo(LocalDateTime.now().minusDays(1)) // expired!
                .isActive(true)
                .build();

        given(voucherRepository.findByCode("OLDVOUCHER")).willReturn(Optional.of(expiredVoucher));

        // Act & Assert
        assertThatThrownBy(() ->
                voucherService.validateVoucher("OLDVOUCHER", 1L, new BigDecimal("300000")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_EXPIRED);
    }

    // =========================================================================
    // Test 10: validateVoucher_belowMinOrder_throwsAppException
    // =========================================================================

    @Test
    void validateVoucher_belowMinOrder_throwsAppException() {
        // Arrange — voucher requires min order of 1,000,000đ but order is only 500,000đ
        Voucher voucher = Voucher.builder()
                .id(3L)
                .code("MINORDER1M")
                .discountType(DiscountType.PERCENT)
                .discountValue(new BigDecimal("15"))
                .maxUses(50)
                .usedCount(0)
                .minOrderAmount(new BigDecimal("1000000")) // min 1,000,000đ
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .isActive(true)
                .build();

        given(voucherRepository.findByCode("MINORDER1M")).willReturn(Optional.of(voucher));

        BigDecimal insufficientOrder = new BigDecimal("500000"); // below minimum

        // Act & Assert
        assertThatThrownBy(() ->
                voucherService.validateVoucher("MINORDER1M", 1L, insufficientOrder))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET);
    }
}