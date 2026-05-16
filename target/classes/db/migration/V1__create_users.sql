-- V1__create_users.sql
CREATE TABLE users (
    id             BIGINT          NOT NULL AUTO_INCREMENT,
    email          VARCHAR(100)    NOT NULL,
    password_hash  VARCHAR(255)    NOT NULL,
    full_name      VARCHAR(100)    NOT NULL,
    role           VARCHAR(20)     NOT NULL COMMENT 'CUSTOMER | OPERATOR | ADMIN',
    is_active      TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
