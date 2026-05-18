-- V4__create_vouchers.sql
CREATE TABLE vouchers (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    code             VARCHAR(50)    NOT NULL,
    discount_type    VARCHAR(20)    NOT NULL COMMENT 'PERCENT | FIXED',
    discount_value   DECIMAL(12, 2) NOT NULL,
    max_uses         INT            COMMENT 'NULL = unlimited',
    used_count       INT            NOT NULL DEFAULT 0,
    min_order_amount DECIMAL(12, 2) COMMENT 'NULL = no minimum',
    valid_from       DATETIME       NOT NULL,
    valid_to         DATETIME       NOT NULL,
    is_active        TINYINT(1)     NOT NULL DEFAULT 1,
    created_at       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_voucher_code (code),
    KEY idx_voucher_valid (valid_from, valid_to, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE voucher_usages (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    voucher_id BIGINT   NOT NULL,
    user_id    BIGINT   NOT NULL,
    booking_id BIGINT   NOT NULL,
    used_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- 1 user chỉ dùng 1 voucher 1 lần — DB-level enforcement
    UNIQUE KEY uk_voucher_user (voucher_id, user_id),
    KEY idx_vu_booking (booking_id),
    CONSTRAINT fk_vu_voucher  FOREIGN KEY (voucher_id)  REFERENCES vouchers (id),
    CONSTRAINT fk_vu_user     FOREIGN KEY (user_id)     REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
