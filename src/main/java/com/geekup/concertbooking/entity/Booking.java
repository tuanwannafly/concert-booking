package com.geekup.concertbooking.entity;

import com.geekup.concertbooking.shared.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "bookings",
    indexes = {
        @Index(name = "idx_booking_user", columnList = "user_id"),
        @Index(name = "idx_booking_concert", columnList = "concert_id"),
        @Index(name = "idx_booking_idempotency", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_booking_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30)")
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Số tiền sau khi áp dụng voucher.
     * Bằng totalAmount nếu không có voucher.
     */
    @Column(name = "final_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id")
    private Voucher voucher;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /**
     * Idempotency key do client gửi lên (UUID v4).
     * UNIQUE constraint → đảm bảo không tạo duplicate booking
     * dù client retry nhiều lần.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    /**
     * Booking sẽ EXPIRED nếu chưa thanh toán trước thời điểm này.
     * Được set = createdAt + 15 phút.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // Lý do huỷ/thất bại — phục vụ Ops dashboard
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /**
     * Flag đánh dấu suspicious booking.
     * Ops có thể filter và xử lý thủ công.
     * Ví dụ: cùng 1 user tạo booking liên tục trong ngắn.
     */
    @Column(name = "is_suspicious", nullable = false)
    @Builder.Default
    private Boolean isSuspicious = false;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BookingItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    
}
