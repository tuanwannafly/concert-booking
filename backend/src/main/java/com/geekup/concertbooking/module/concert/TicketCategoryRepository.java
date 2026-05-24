package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.entity.TicketCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketCategoryRepository extends JpaRepository<TicketCategory, Long> {

    List<TicketCategory> findByConcertId(Long concertId);

    /**
     * Dùng để xác minh category thuộc về concert cụ thể,
     * tránh trường hợp client truyền categoryId của concert khác.
     */
    List<TicketCategory> findByConcertIdAndId(Long concertId, Long id);
}
