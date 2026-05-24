package com.geekup.concertbooking.shared.enums;

public enum BookingStatus {
    PENDING,            // Vừa tạo, đang giữ vé (chưa thanh toán)
    WAITING_PAYMENT,    // Đã xác nhận booking, chờ thanh toán
    PAID,               // Đã thanh toán thành công
    COMPLETED,          // Concert đã diễn ra, booking hoàn tất
    CANCELLED,          // User hoặc Ops đã huỷ
    EXPIRED             // Hết 15 phút không thanh toán, auto-expire
}
