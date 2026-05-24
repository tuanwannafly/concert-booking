package com.geekup.concertbooking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "ticket_categories",
    indexes = {
        @Index(name = "idx_ticket_category_concert", columnList = "concert_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @Column(nullable = false, length = 100)
    private String name;  // VIP, Standard, SVIP...

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    /**
     * Số vé còn lại trong DB.
     * Đây là "source of truth" sau khi booking được confirm.
     * Redis inventory counter là lớp fast-check trước DB.
     */
    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    /**
     * @Version → Optimistic Locking.
     * Nếu 2 transaction cùng update availableQuantity,
     * transaction sau sẽ bị OptimisticLockException → retry.
     * Đây là safety net ngoài Redis DECRBY.
     */
    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "max_per_booking", nullable = false)
    @Builder.Default
    private Integer maxPerBooking = 4;  // 1 booking tối đa 4 vé/category

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
