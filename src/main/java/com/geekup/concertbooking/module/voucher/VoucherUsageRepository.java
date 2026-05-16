package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.VoucherUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoucherUsageRepository extends JpaRepository<VoucherUsage, Long> {

    /**
     * Kiểm tra user đã từng dùng voucher này chưa.
     * Kết hợp với UNIQUE(voucher_id, user_id) ở DB để đảm bảo 1 user / 1 voucher.
     */
    boolean existsByVoucherIdAndUserId(Long voucherId, Long userId);
}
