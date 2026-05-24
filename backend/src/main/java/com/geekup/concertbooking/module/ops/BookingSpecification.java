package com.geekup.concertbooking.module.ops;

import com.geekup.concertbooking.entity.Booking;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specification factory cho Booking entity.
 *
 * Dùng kết hợp bằng Specification.where().and():
 * <pre>{@code
 *   Specification<Booking> spec = Specification
 *       .where(BookingSpecification.hasConcertId(concertId))
 *       .and(BookingSpecification.hasStatus(status))
 *       .and(BookingSpecification.isSuspicious(flag));
 * }</pre>
 *
 * QUAN TRỌNG: BookingRepository phải extends JpaSpecificationExecutor<Booking>
 * để dùng findAll(Specification, Pageable). Xem BookingRepository section ở cuối.
 */
public class BookingSpecification {

    private BookingSpecification() {
        // utility class — không khởi tạo
    }

    // =========================================================================
    //  Filter specs
    // =========================================================================

    /**
     * Filter theo concert.
     * Trả về null-safe spec — nếu concertId == null thì không áp filter.
     */
    public static Specification<Booking> hasConcertId(Long concertId) {
        return (root, query, cb) -> {
            if (concertId == null) return cb.conjunction(); // no-op
            // Join lazy concert để lấy id — dùng fetch join sẽ conflict với count query,
            // nên chỉ join thường ở đây.
            return cb.equal(root.get("concert").get("id"), concertId);
        };
    }

    /**
     * Filter theo status cụ thể.
     * Trả về no-op nếu status == null.
     */
    public static Specification<Booking> hasStatus(BookingStatus status) {
        return (root, query, cb) -> {
            if (status == null) return cb.conjunction();
            return cb.equal(root.get("status"), status);
        };
    }

    /**
     * Filter theo flag isSuspicious.
     * - flag = true  → chỉ lấy booking bị đánh dấu suspicious
     * - flag = false → chỉ lấy booking bình thường
     * - flag = null  → không filter (lấy tất cả)
     */
    public static Specification<Booking> isSuspicious(Boolean flag) {
        return (root, query, cb) -> {
            if (flag == null) return cb.conjunction();
            return cb.equal(root.get("isSuspicious"), flag);
        };
    }
}
