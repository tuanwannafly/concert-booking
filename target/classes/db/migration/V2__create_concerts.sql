-- V2__create_concerts.sql
CREATE TABLE concerts (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(200) NOT NULL,
    venue        VARCHAR(300) NOT NULL,
    event_date   DATETIME     NOT NULL,
    description  TEXT,
    banner_url   VARCHAR(500),
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED | ENDED',
    published_at DATETIME,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_concerts_status (status),
    KEY idx_concerts_event_date (event_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
