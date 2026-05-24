-- V5__create_bookings.sql
CREATE TABLE bookings (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    user_id          BIGINT         NOT NULL,
    concert_id       BIGINT         NOT NULL,
    status           VARCHAR(30)    NOT NULL DEFAULT 'PENDING'
                         COMMENT 'PENDING | WAITING_PAYMENT | PAID | COMPLETED | CANCELLED | EXPIRED',
    total_amount     DECIMAL(12, 2) NOT NULL COMMENT 'Tổng trước giảm giá',
    final_amount     DECIMAL(12, 2) NOT NULL COMMENT 'Tổng sau giảm giá (số tiền cần thanh toán)',
    voucher_id       BIGINT         COMMENT 'NULL nếu không dùng voucher',
    discount_amount  DECIMAL(12, 2) COMMENT 'NULL nếu không có voucher',
    -- Idempotency key (UUID v4) từ client
    -- UNIQUE đảm bảo không tạo duplicate dù retry nhiều lần
    idempotency_key  VARCHAR(100)   NOT NULL,
    expires_at       DATETIME       COMMENT 'PENDING → EXPIRED nếu quá thời điểm này',
    paid_at          DATETIME,
    cancelled_at     DATETIME,
    cancel_reason    VARCHAR(500),
    is_suspicious    TINYINT(1)     NOT NULL DEFAULT 0,
    created_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_idempotency (idempotency_key),
    KEY idx_booking_user    (user_id),
    KEY idx_booking_concert (concert_id),
    KEY idx_booking_status  (status),
    KEY idx_booking_expires (expires_at, status),
    CONSTRAINT fk_booking_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_booking_concert FOREIGN KEY (concert_id) REFERENCES concerts (id),
    CONSTRAINT fk_booking_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking_items (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    booking_id          BIGINT         NOT NULL,
    ticket_category_id  BIGINT         NOT NULL,
    quantity            INT            NOT NULL,
    unit_price          DECIMAL(12, 2) NOT NULL COMMENT 'Snapshot giá lúc booking',
    subtotal            DECIMAL(12, 2) NOT NULL COMMENT 'quantity * unit_price',

    PRIMARY KEY (id),
    KEY idx_bi_booking  (booking_id),
    KEY idx_bi_category (ticket_category_id),
    CONSTRAINT fk_bi_booking   FOREIGN KEY (booking_id)         REFERENCES bookings (id),
    CONSTRAINT fk_bi_category  FOREIGN KEY (ticket_category_id) REFERENCES ticket_categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add FK từ voucher_usages → bookings (sau khi bookings table tồn tại)
ALTER TABLE voucher_usages
    ADD CONSTRAINT fk_vu_booking FOREIGN KEY (booking_id) REFERENCES bookings (id);
