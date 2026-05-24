-- V3__create_ticket_categories.sql
CREATE TABLE ticket_categories (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    concert_id         BIGINT         NOT NULL,
    name               VARCHAR(100)   NOT NULL,
    price              DECIMAL(12, 2) NOT NULL,
    total_quantity     INT            NOT NULL,
    available_quantity INT            NOT NULL,
    version            INT            NOT NULL DEFAULT 0 COMMENT 'Optimistic locking version',
    max_per_booking    INT            NOT NULL DEFAULT 4,
    created_at         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_ticket_category_concert (concert_id),
    CONSTRAINT fk_tc_concert FOREIGN KEY (concert_id) REFERENCES concerts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
