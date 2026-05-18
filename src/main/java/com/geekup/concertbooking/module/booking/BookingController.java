package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.module.booking.dto.*;
// ✅ FIX: Không import app ApiResponse ở đây — dùng FQN trong return types
//         để tránh collision với Swagger @ApiResponse annotation bên dưới.
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;       // Swagger annotation
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

// App ApiResponse — aliased via FQN in every return type, no import needed.
// This is the standard Java pattern when two classes share the same simple name.

@Tag(name = "Booking", description = "Đặt vé, thanh toán và quản lý booking cá nhân")
@SecurityRequirement(name = "bearerAuth")   // must match the scheme name in SwaggerConfig
@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("isAuthenticated()")
public class BookingController {

    // Explicit logger — avoids @Slf4j Lombok dependency that NetBeans LS cannot process.
    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingService bookingService;
    private final UserIdResolver userIdResolver;

    // Explicit constructor — replaces @RequiredArgsConstructor.
    // Spring injects both beans automatically via constructor injection.
    public BookingController(BookingService bookingService, UserIdResolver userIdResolver) {
        this.bookingService  = bookingService;
        this.userIdResolver  = userIdResolver;
    }

    // ------------------------------------------------------------------
    //  POST /api/v1/bookings  — Tạo booking mới
    // ------------------------------------------------------------------

    @Operation(
        summary = "Tạo booking mới",
        description = """
            Đặt vé concert. Client **bắt buộc** gửi header `X-Idempotency-Key` (UUID v4)
            để đảm bảo retry-safe — nếu gửi lại cùng key sẽ nhận về response cũ,
            không tạo booking mới.

            Booking có hiệu lực 15 phút kể từ khi tạo. Nếu chưa thanh toán
            trong 15 phút, booking sẽ tự động EXPIRED và tồn kho được hoàn lại.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Booking tạo thành công"),
        @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ / concert chưa publish / vượt quá giới hạn vé"),
        @ApiResponse(responseCode = "409", description = "Vé đã hết / voucher đã dùng / booking đã tồn tại"),
        @ApiResponse(responseCode = "410", description = "Voucher hoặc booking đã hết hạn")
    })
    @PostMapping
    public ResponseEntity<com.geekup.concertbooking.shared.response.ApiResponse<BookingResponse>> createBooking(
            @Parameter(description = "UUID v4 do client sinh, dùng cho idempotency", required = true)
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        log.debug("POST /bookings — user={} idempotencyKey={}", userId, idempotencyKey);

        BookingResponse response = bookingService.createBooking(request, userId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(com.geekup.concertbooking.shared.response.ApiResponse.success(response, "Booking tạo thành công"));
    }

    // ------------------------------------------------------------------
    //  GET /api/v1/bookings  — Lịch sử booking của user
    // ------------------------------------------------------------------

    @Operation(
        summary = "Danh sách booking của tôi",
        description = "Trả về lịch sử booking của user đang đăng nhập, sắp xếp theo thời gian tạo giảm dần."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Thành công"),
        @ApiResponse(responseCode = "401", description = "Chưa xác thực")
    })
    @GetMapping
    public ResponseEntity<com.geekup.concertbooking.shared.response.ApiResponse<Page<BookingResponse>>> getMyBookings(
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0")  int page,
            @Parameter(description = "Kích thước trang")         @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<BookingResponse> result = bookingService.getMyBookings(userId, pageable);
        return ResponseEntity.ok(com.geekup.concertbooking.shared.response.ApiResponse.success(result));
    }

    // ------------------------------------------------------------------
    //  GET /api/v1/bookings/{id}  — Chi tiết 1 booking
    // ------------------------------------------------------------------

    @Operation(
        summary = "Chi tiết booking",
        description = "Lấy thông tin đầy đủ của 1 booking. Chỉ có thể xem booking của chính mình."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Thành công"),
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn (BOOKING_NOT_OWNED)"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại (BOOKING_NOT_FOUND)")
    })
    @GetMapping("/{id}")
    public ResponseEntity<com.geekup.concertbooking.shared.response.ApiResponse<BookingResponse>> getBookingById(
            @Parameter(description = "Booking ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        BookingResponse response = bookingService.getBookingById(id, userId);
        return ResponseEntity.ok(com.geekup.concertbooking.shared.response.ApiResponse.success(response));
    }

    // ------------------------------------------------------------------
    //  POST /api/v1/bookings/{id}/pay  — Thanh toán
    // ------------------------------------------------------------------

    @Operation(
        summary = "Thanh toán booking (mock)",
        description = """
            Giả lập thanh toán booking. Booking phải ở trạng thái PENDING
            và chưa hết hạn 15 phút. Chuyển trạng thái sang WAITING_PAYMENT.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Thanh toán thành công"),
        @ApiResponse(responseCode = "409", description = "Trạng thái không hợp lệ (BOOKING_INVALID_STATUS)"),
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn (BOOKING_NOT_OWNED)"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại (BOOKING_NOT_FOUND)")
    })
    @PostMapping("/{id}/pay")
    public ResponseEntity<com.geekup.concertbooking.shared.response.ApiResponse<PaymentResponse>> pay(
            @Parameter(description = "Booking ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        log.debug("POST /bookings/{}/pay — user={}", id, userId);

        PaymentResponse response = bookingService.pay(id, userId);
        return ResponseEntity.ok(
            com.geekup.concertbooking.shared.response.ApiResponse.success(response, "Thanh toán thành công"));
    }

    // ------------------------------------------------------------------
    //  DELETE /api/v1/bookings/{id}  — Huỷ booking
    // ------------------------------------------------------------------

    @Operation(
        summary = "Huỷ booking",
        description = """
            Huỷ booking ở trạng thái PENDING hoặc WAITING_PAYMENT.
            Tồn kho sẽ được hoàn lại ngay lập tức vào Redis và DB.
            Không thể huỷ booking đã PAID, COMPLETED, CANCELLED, hoặc EXPIRED.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Huỷ thành công"),
        @ApiResponse(responseCode = "409", description = "Không thể huỷ ở trạng thái hiện tại (BOOKING_CANNOT_CANCEL)"),
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn (BOOKING_NOT_OWNED)"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại (BOOKING_NOT_FOUND)")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<com.geekup.concertbooking.shared.response.ApiResponse<BookingResponse>> cancelBooking(
            @Parameter(description = "Booking ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        log.debug("DELETE /bookings/{} — user={}", id, userId);

        BookingResponse response = bookingService.cancelBooking(id, userId);
        return ResponseEntity.ok(
            com.geekup.concertbooking.shared.response.ApiResponse.success(response, "Huỷ booking thành công"));
    }
}