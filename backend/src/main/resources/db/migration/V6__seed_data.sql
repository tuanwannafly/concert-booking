-- V6__seed_data.sql
-- Password: "password123" (BCrypt $2a$10$...)
-- Dùng cho local dev & Postman testing

-- =====================
-- USERS
-- =====================
INSERT INTO users (email, password_hash, full_name, role) VALUES
-- ADMIN
('admin@geekup.vn',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVyc5EJXXu', 'Admin GEEK Up',   'ADMIN'),
-- OPERATORS
('operator1@geekup.vn','$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVyc5EJXXu', 'Operator One',   'OPERATOR'),
-- CUSTOMERS
('customer1@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVyc5EJXXu', 'Nguyen Van A',   'CUSTOMER'),
('customer2@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVyc5EJXXu', 'Tran Thi B',     'CUSTOMER'),
('customer3@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVyc5EJXXu', 'Le Van C',       'CUSTOMER');

-- =====================
-- CONCERTS
-- =====================
INSERT INTO concerts (name, venue, event_date, description, status, published_at) VALUES
(
    'AIGHT FLASH SALE CONCERT 2026',
    'Nhà Thi Đấu Phú Thọ, TP.HCM',
    '2026-08-15 19:00:00',
    'Đêm nhạc hội tụ các nghệ sĩ hàng đầu Việt Nam. Flash sale giới hạn!',
    'PUBLISHED',
    NOW()
),
(
    'HANOIFEST 2026',
    'Trung tâm Hội nghị Quốc gia, Hà Nội',
    '2026-09-20 18:30:00',
    'Lễ hội âm nhạc lớn nhất miền Bắc năm 2026.',
    'PUBLISHED',
    NOW()
),
(
    'UPCOMING SHOW - DRAFT',
    'Sân khấu TBD',
    '2026-12-31 21:00:00',
    'Concert đang được lên kế hoạch.',
    'DRAFT',
    NULL
);

-- =====================
-- TICKET CATEGORIES
-- Concert 1: Flash Sale
-- =====================
INSERT INTO ticket_categories (concert_id, name, price, total_quantity, available_quantity, version, max_per_booking)
VALUES
(1, 'SVIP',     2500000.00, 100,  100,  0, 2),
(1, 'VIP',      1500000.00, 300,  300,  0, 4),
(1, 'Standard',  800000.00, 1000, 1000, 0, 4),
-- Concert 2
(2, 'VIP',      1200000.00, 200,  200,  0, 4),
(2, 'Standard',  600000.00, 800,  800,  0, 4);

-- =====================
-- VOUCHERS
-- =====================
INSERT INTO vouchers (code, discount_type, discount_value, max_uses, used_count, min_order_amount, valid_from, valid_to, is_active)
VALUES
-- Flash sale voucher: giảm 10%, tối đa 200 lần dùng
('FLASHSALE10',  'PERCENT', 10.00, 200, 0, 500000.00,  '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1),
-- VIP voucher: giảm cố định 200k, chỉ 50 lần
('VIP200K',      'FIXED',   200000.00, 50, 0, 1500000.00, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1),
-- Expired voucher (để test validation)
('EXPIRED2025',  'PERCENT', 15.00, 100, 0, NULL,       '2025-01-01 00:00:00', '2025-12-31 23:59:59', 1),
-- Inactive voucher
('INACTIVE_TEST','FIXED',   50000.00, NULL, 0, NULL,   '2026-01-01 00:00:00', '2026-12-31 23:59:59', 0);
