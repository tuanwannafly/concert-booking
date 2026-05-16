package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository cho Voucher entity.
 *
 * NOTE: findByCodeAndIsActiveTrue() là query chính dùng trong validate flow.
 * findByCode() (không lọc active) dùng khi cần kiểm tra code đã tồn tại hay chưa
 * — ví dụ: trước khi createVoucher.
 */
@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    /**
     * Tìm voucher theo code (case-sensitive, unique).
     * Trả về kể cả voucher đã inactive — dùng để check duplicate code khi tạo mới.
     */
    Optional<Voucher> findByCode(String code);

    /**
     * Tìm voucher theo code VÀ đang active.
     * Query chính dùng trong validate — không trả về voucher bị disable.
     */
    Optional<Voucher> findByCodeAndIsActiveTrue(String code);

    /**
     * Kiểm tra code đã tồn tại chưa (kể cả inactive).
     * Dùng để validate unique khi Admin tạo voucher mới.
     */
    boolean existsByCode(String code);
}
