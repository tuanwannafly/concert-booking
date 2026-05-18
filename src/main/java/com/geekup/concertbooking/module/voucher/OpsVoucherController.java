package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.module.voucher.dto.CreateVoucherRequest;
import com.geekup.concertbooking.module.voucher.dto.VoucherResponse;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.*;

/**
 * Ops (Admin-only) voucher management endpoints.
 *
 * Tất cả endpoint yêu cầu role ADMIN — được enforce bởi @PreAuthorize("hasRole('ADMIN')")
 * kết hợp với @EnableMethodSecurity trong SecurityConfig.
 *
 * Scope:
 *   - POST /  → Tạo voucher mới
 *   - GET  /  → Liệt kê tất cả voucher (có pagination)
 *
 * Out of scope (xem assumptions.md):
 *   - PUT / PATCH  → Update voucher (không hỗ trợ)
 *   - DELETE       → Delete voucher (không hỗ trợ)
 */
@Tag(name = "Ops - Voucher", description = "Quản lý voucher (Admin only)")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/ops/vouchers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class OpsVoucherController {

    private final VoucherService voucherService;

    // ------------------------------------------------------------------
    //  POST /api/v1/ops/vouchers — Tạo voucher mới
    // ------------------------------------------------------------------

    @Operation(
        summary = "Tạo voucher mới",
        description = """
            Tạo một voucher mới trong hệ thống. Chỉ Admin mới có quyền thực hiện.
            
            - Code phải unique (kể cả với voucher đã inactive).
            - Voucher **không thể chỉnh sửa** sau khi tạo (xem assumptions.md).
            - discountValue cho PERCENT phải trong khoảng 1–100.
            - validFrom phải trước validTo.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Voucher tạo thành công"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Dữ liệu không hợp lệ"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Không có quyền Admin"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Code đã tồn tại (dùng NOT_FOUND error code)"
        )
    })
    @PostMapping
    public ResponseEntity<ApiResponse<VoucherResponse>> createVoucher(
            @Valid @RequestBody CreateVoucherRequest request) {

        log.info("Admin POST /api/v1/ops/vouchers code={}", request.code());

        VoucherResponse response = voucherService.createVoucher(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response, "Voucher tạo thành công"));
    }

    // ------------------------------------------------------------------
    //  GET /api/v1/ops/vouchers — Liệt kê tất cả voucher
    // ------------------------------------------------------------------

    @Operation(
        summary = "Liệt kê tất cả voucher",
        description = """
            Trả về danh sách tất cả voucher với pagination.
            Mỗi voucher có trường `status` tính toán động:
            - `ACTIVE`    : đang hoạt động, còn hạn, còn lượt dùng
            - `EXPIRED`   : đã hết hạn (validTo đã qua)
            - `INACTIVE`  : bị vô hiệu hóa (isActive=false)
            - `EXHAUSTED` : đã hết lượt dùng (usedCount >= maxUses)
            
            Sort mặc định: createdAt DESC.
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Danh sách voucher (Page)"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Không có quyền Admin"
        )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Page<VoucherResponse>>> listVouchers(
            @Parameter(description = "Số trang (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Kích thước trang", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        log.debug("Admin GET /api/v1/ops/vouchers page={} size={}", page, size);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<VoucherResponse> result = voucherService.listVouchers(pageable);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
