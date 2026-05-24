package com.geekup.concertbooking.module.ops;

import com.geekup.concertbooking.module.ops.dto.OpsBookingDTO.*;
import com.geekup.concertbooking.shared.enums.BookingStatus;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Ops - Booking", description = "Quản lý booking (yêu cầu quyền OPERATOR hoặc ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/ops/bookings")
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
@RequiredArgsConstructor
@Validated
public class OpsBookingController {

    private final OpsBookingService opsBookingService;

    // =========================================================================
    //  GET /api/v1/ops/bookings
    // =========================================================================

    @Operation(
        summary = "Danh sách booking",
        description = """
            Lấy danh sách booking với filter tùy chọn.
            Tất cả params đều optional — nếu bỏ qua thì không áp filter đó.
            Default sort: `createdAt DESC`.
            """
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page<BookingListResponse>>> listBookings(

        @Parameter(description = "Filter theo concert ID")
        @RequestParam(required = false) Long concertId,

        @Parameter(description = "Filter theo trạng thái booking")
        @RequestParam(required = false) BookingStatus status,

        @Parameter(description = "true = chỉ lấy suspicious, false = chỉ lấy bình thường, bỏ qua = tất cả")
        @RequestParam(required = false) Boolean isSuspicious,

        @RequestParam(defaultValue = "0")  int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        // Enforce default sort: createdAt DESC — ops cần xem booking mới nhất trước
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<BookingListResponse> result =
            opsBookingService.listBookings(concertId, status, isSuspicious, pageable);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // =========================================================================
    //  GET /api/v1/ops/bookings/suspicious
    // =========================================================================

    @Operation(
        summary = "Danh sách suspicious bookings",
        description = """
            Shortcut endpoint — lấy tất cả booking được đánh dấu isSuspicious=true.
            Dùng cho ops triage nhanh mà không cần nhớ query param.
            """
    )
    @GetMapping("/suspicious")
    public ResponseEntity<ApiResponse<Page<BookingListResponse>>> getSuspiciousBookings(
        @RequestParam(defaultValue = "0")  int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BookingListResponse> result = opsBookingService.getSuspiciousBookings(pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // =========================================================================
    //  GET /api/v1/ops/bookings/stats
    // =========================================================================

    @Operation(
        summary = "Thống kê booking theo concert",
        description = """
            Tổng hợp số lượng booking theo từng trạng thái và tổng doanh thu
            (PAID + COMPLETED) cho 1 concert. Dùng cho Ops Dashboard overview.
            `concertId` là bắt buộc.
            """
    )
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<BookingStatsResponse>> getBookingStats(

        @Parameter(description = "Concert ID (bắt buộc)", required = true)
        @RequestParam Long concertId

    ) {
        BookingStatsResponse stats = opsBookingService.getBookingStats(concertId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // =========================================================================
    //  GET /api/v1/ops/bookings/{id}
    // =========================================================================

    @Operation(
        summary = "Chi tiết booking",
        description = """
            Lấy đầy đủ thông tin 1 booking bao gồm items, timestamps, cancel reason.
            Ops không cần ownership — có thể xem bất kỳ booking nào.
            """
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> getBookingDetail(
        @Parameter(description = "Booking ID") @PathVariable Long id
    ) {
        BookingDetailResponse detail = opsBookingService.getBookingDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    // =========================================================================
    //  PUT /api/v1/ops/bookings/{id}/status
    // =========================================================================

    @Operation(
        summary = "Cập nhật status booking thủ công",
        description = """
            Ops có thể override status booking theo các transition được phép:
            - Bất kỳ trạng thái (trừ COMPLETED, EXPIRED) → **CANCELLED** (nên kèm reason)
            - **PAID** → **COMPLETED**

            Không thể thay đổi booking đã COMPLETED hoặc EXPIRED.

            Nếu cancel booking đang PENDING/WAITING_PAYMENT, inventory sẽ được
            rollback tự động (Redis + DB).
            """
    )
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<BookingDetailResponse>> manualUpdateStatus(
        @Parameter(description = "Booking ID") @PathVariable Long id,
        @Valid @RequestBody UpdateBookingStatusRequest request
    ) {
        BookingDetailResponse detail = opsBookingService.manualUpdateStatus(id, request);
        return ResponseEntity.ok(
            ApiResponse.success(detail,
                "Booking " + id + " đã được chuyển sang " + request.status())
        );
    }
}
