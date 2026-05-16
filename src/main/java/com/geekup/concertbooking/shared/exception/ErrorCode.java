package com.geekup.concertbooking.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Auth
    INVALID_CREDENTIALS     (HttpStatus.UNAUTHORIZED,  "Email hoặc mật khẩu không đúng"),
    TOKEN_EXPIRED           (HttpStatus.UNAUTHORIZED,  "Token đã hết hạn"),
    TOKEN_INVALID           (HttpStatus.UNAUTHORIZED,  "Token không hợp lệ"),
    ACCESS_DENIED           (HttpStatus.FORBIDDEN,     "Bạn không có quyền thực hiện hành động này"),

    // User
    USER_NOT_FOUND          (HttpStatus.NOT_FOUND,     "Không tìm thấy người dùng"),
    EMAIL_ALREADY_EXISTS    (HttpStatus.CONFLICT,      "Email đã được sử dụng"),

    // Concert
    CONCERT_NOT_FOUND       (HttpStatus.NOT_FOUND,     "Không tìm thấy concert"),
    CONCERT_NOT_PUBLISHED   (HttpStatus.BAD_REQUEST,   "Concert chưa được public"),
    INVALID_CONCERT_STATUS  (HttpStatus.BAD_REQUEST,   "Trạng thái concert không hợp lệ cho thao tác này"),

    // Ticket Category
    TICKET_CATEGORY_NOT_FOUND  (HttpStatus.NOT_FOUND,  "Không tìm thấy loại vé"),
    TICKET_SOLD_OUT            (HttpStatus.CONFLICT,    "Vé đã hết, không thể đặt thêm"),
    TICKET_QUANTITY_EXCEEDED   (HttpStatus.BAD_REQUEST, "Số lượng vé vượt quá giới hạn cho phép mỗi lần đặt"),
    INSUFFICIENT_INVENTORY     (HttpStatus.CONFLICT,    "Không đủ số lượng vé yêu cầu"),

    // Booking
    BOOKING_NOT_FOUND          (HttpStatus.NOT_FOUND,   "Không tìm thấy đơn đặt vé"),
    BOOKING_ALREADY_EXISTS     (HttpStatus.CONFLICT,    "Đơn đặt vé đã tồn tại (idempotency)"),
    BOOKING_CANNOT_CANCEL      (HttpStatus.BAD_REQUEST, "Không thể huỷ đơn đặt vé ở trạng thái này"),
    BOOKING_EXPIRED            (HttpStatus.GONE,        "Đơn đặt vé đã hết hạn"),
    BOOKING_INVALID_STATUS     (HttpStatus.BAD_REQUEST, "Chuyển trạng thái không hợp lệ"),
    BOOKING_NOT_OWNED          (HttpStatus.FORBIDDEN,   "Đơn đặt vé không thuộc về bạn"),

    // Voucher
    VOUCHER_NOT_FOUND          (HttpStatus.NOT_FOUND,   "Mã voucher không tồn tại"),
    VOUCHER_EXPIRED            (HttpStatus.BAD_REQUEST, "Voucher đã hết hạn"),
    VOUCHER_INACTIVE           (HttpStatus.BAD_REQUEST, "Voucher không còn hoạt động"),
    VOUCHER_EXHAUSTED          (HttpStatus.CONFLICT,    "Voucher đã hết lượt sử dụng"),
    VOUCHER_MIN_ORDER_NOT_MET  (HttpStatus.BAD_REQUEST, "Giá trị đơn hàng chưa đạt mức tối thiểu để dùng voucher"),
    VOUCHER_ALREADY_USED       (HttpStatus.CONFLICT,    "Bạn đã sử dụng voucher này rồi"),

    // Generic
    VALIDATION_ERROR           (HttpStatus.BAD_REQUEST, "Dữ liệu đầu vào không hợp lệ"),
    INTERNAL_SERVER_ERROR      (HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống, vui lòng thử lại sau");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
