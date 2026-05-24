package com.geekup.concertbooking.module.voucher;

import com.geekup.concertbooking.module.booking.UserIdResolver;
import com.geekup.concertbooking.module.voucher.dto.ValidateVoucherRequest;
import com.geekup.concertbooking.module.voucher.dto.ValidateVoucherResponse;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Customer-facing voucher endpoints.
 *
 * Tất cả endpoint yêu cầu authentication (JWT hợp lệ).
 * Không yêu cầu role ADMIN — mọi user đăng nhập đều có thể validate voucher.
 */
@Tag(name = "Voucher", description = "Validate và preview mã giảm giá trước khi đặt vé")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/vouchers")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class VoucherController {

    private final VoucherService  voucherService;
    private final UserIdResolver  userIdResolver;

    // ------------------------------------------------------------------
    //  POST /api/v1/vouchers/validate — Preview giảm giá
    // ------------------------------------------------------------------

    @Operation(
        summary = "Validate và preview voucher",
        description = """
            Kiểm tra tính hợp lệ của voucher với đơn hàng cụ thể và tính toán
            số tiền giảm. **Không apply vào DB** — chỉ là preview trước khi tạo booking.
            
            Các lỗi có thể xảy ra:
            - `VOUCHER_NOT_FOUND`: code không tồn tại
            - `VOUCHER_INACTIVE`: voucher bị vô hiệu hóa
            - `VOUCHER_EXPIRED`: ngoài thời hạn hiệu lực
            - `VOUCHER_EXHAUSTED`: đã hết lượt dùng toàn hệ thống
            - `VOUCHER_MIN_ORDER_NOT_MET`: đơn hàng chưa đạt mức tối thiểu
            - `VOUCHER_ALREADY_USED`: bạn đã dùng voucher này rồi
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Voucher hợp lệ — trả về discountAmount và finalAmount"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Voucher inactive / expired / min order not met"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Voucher không tồn tại"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Voucher đã hết lượt dùng / bạn đã dùng rồi"
        )
    })
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<ValidateVoucherResponse>> validateVoucher(
            @Valid @RequestBody ValidateVoucherRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = userIdResolver.resolveUserId(userDetails);
        log.debug("POST /api/v1/vouchers/validate userId={} code={}", userId, request.voucherCode());

        ValidateVoucherResponse response = voucherService.validateVoucher(
            request.voucherCode(),
            userId,
            request.orderAmount()
        );

        return ResponseEntity.ok(ApiResponse.success(response, "Voucher hợp lệ"));
    }
}
