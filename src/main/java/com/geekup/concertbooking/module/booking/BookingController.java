package com.geekup.concertbooking.module.booking;

import com.geekup.concertbooking.module.booking.dto.*;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Tag(name = "Booking", description = "Đặt vé, thanh toán và quản lý booking cá nhân")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingService bookingService;
    private final UserIdResolver userIdResolver;  // helper lấy userId từ UserDetails

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
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0") int page,
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
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại")
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
        summary = "Thanh toán booking",
        description = """
            Giả lập thanh toán booking (Phase 3). Phase 4 sẽ tích hợp payment gateway thật.
            
            Booking phải ở trạng thái PENDING hoặc WAITING_PAYMENT và chưa hết hạn.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Thanh toán thành công"),
        @ApiResponse(responseCode = "400", description = "Booking ở trạng thái không hợp lệ"),
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại"),
        @ApiResponse(responseCode = "410", description = "Booking đã hết hạn")
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
            Tồn kho sẽ được hoàn lại ngay lập tức.
            Không thể huỷ booking đã PAID, COMPLETED, CANCELLED, hoặc EXPIRED.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Huỷ thành công"),
        @ApiResponse(responseCode = "400", description = "Không thể huỷ ở trạng thái hiện tại"),
        @ApiResponse(responseCode = "403", description = "Booking không thuộc về bạn"),
        @ApiResponse(responseCode = "404", description = "Booking không tồn tại")
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
