package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    /**
     * Tìm voucher theo code (case-sensitive, unique).
     */
    Optional<Voucher> findByCode(String code);

    /**
     * Tìm voucher theo code VÀ đang active.
     * Đây là query chính dùng trong validate — không trả về voucher đã bị disable.
     */
    Optional<Voucher> findByCodeAndIsActiveTrue(String code);
}
