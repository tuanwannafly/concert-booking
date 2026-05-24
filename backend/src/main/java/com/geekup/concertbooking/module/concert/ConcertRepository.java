package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConcertRepository extends JpaRepository<Concert, Long> {

    Page<Concert> findByStatus(ConcertStatus status, Pageable pageable);

    Optional<Concert> findByIdAndStatus(Long id, ConcertStatus status);
}
