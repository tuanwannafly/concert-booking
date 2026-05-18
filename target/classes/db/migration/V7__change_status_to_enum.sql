-- V7: Chuyển bookings.status từ VARCHAR(30) sang ENUM
-- Migration này được revert bởi V8 (ENUM gây khó khăn khi thêm trạng thái mới).
-- Giữ lại để Flyway checksum history không bị gap giữa V6 và V8.

ALTER TABLE bookings
    MODIFY COLUMN status ENUM(
        'PENDING',
        'WAITING_PAYMENT',
        'PAID',
        'COMPLETED',
        'CANCELLED',
        'EXPIRED'
    ) NOT NULL DEFAULT 'PENDING';
