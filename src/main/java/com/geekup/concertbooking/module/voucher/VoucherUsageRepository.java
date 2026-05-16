package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.VoucherUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository cho VoucherUsage entity.
 *
 * DB có UNIQUE constraint (voucher_id, user_id) — đảm bảo 1 user chỉ dùng 1 voucher 1 lần.
 * existsByVoucherIdAndUserId() hoạt động như "pre-check" trước khi insert.
 * Redis lock ở BookingService bảo vệ race condition khi insert đồng thời.
 */
@Repository
public interface VoucherUsageRepository extends JpaRepository<VoucherUsage, Long> {

    /**
     * Kiểm tra user đã từng dùng voucher này chưa.
     * Kết hợp với UNIQUE(voucher_id, user_id) ở DB để đảm bảo 1 user / 1 voucher.
     */
    boolean existsByVoucherIdAndUserId(Long voucherId, Long userId);

    /**
     * Lấy toàn bộ lịch sử dùng voucher của một user.
     * Optional — dùng cho stats / profile page.
     */
    List<VoucherUsage> findByUserId(Long userId);
}
