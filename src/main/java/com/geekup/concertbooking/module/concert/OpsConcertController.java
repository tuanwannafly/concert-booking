package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.module.concert.dto.*;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Ops endpoints — chỉ OPERATOR và ADMIN mới truy cập được.
 * @PreAuthorize trên class áp dụng cho tất cả các method.
 */
@Tag(name = "Ops - Concert", description = "Quản lý concert (yêu cầu quyền OPERATOR hoặc ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/ops/concerts")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class OpsConcertController {

    private final ConcertService concertService;

    @Operation(
        summary = "Tạo concert mới",
        description = "Tạo concert với status=DRAFT. Chưa hiển thị cho customer cho đến khi publish."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<ConcertResponse>> createConcert(
            @Valid @RequestBody CreateConcertRequest request) {

        ConcertResponse response = concertService.createConcert(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Tạo concert thành công"));
    }

    @Operation(
        summary = "Cập nhật concert",
        description = "Partial update — chỉ update các field được gửi lên. Chỉ hoạt động khi concert đang DRAFT."
    )
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ConcertResponse>> updateConcert(
            @Parameter(description = "Concert ID") @PathVariable Long id,
            @Valid @RequestBody UpdateConcertRequest request) {

        ConcertResponse response = concertService.updateConcert(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật concert thành công"));
    }

    @Operation(
        summary = "Publish concert",
        description = "Chuyển concert từ DRAFT → PUBLISHED và load inventory vào Redis. " +
                      "Yêu cầu concert phải có ít nhất 1 loại vé."
    )
    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<ConcertDetailResponse>> publishConcert(
            @Parameter(description = "Concert ID") @PathVariable Long id) {

        ConcertDetailResponse response = concertService.publishConcert(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Concert đã được publish thành công"));
    }

    @Operation(
        summary = "Thêm loại vé",
        description = "Thêm ticket category vào concert. Chỉ cho phép khi concert đang DRAFT."
    )
    @PostMapping("/{id}/categories")
    public ResponseEntity<ApiResponse<TicketCategoryResponse>> addTicketCategory(
            @Parameter(description = "Concert ID") @PathVariable Long id,
            @Valid @RequestBody AddTicketCategoryRequest request) {

        TicketCategoryResponse response = concertService.addTicketCategory(id, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Thêm loại vé thành công"));
    }

    @Operation(
        summary = "Reload inventory từ DB vào Redis",
        description = """
            Re-seed inventory counters vào Redis từ DB source-of-truth.
            Dùng khi Redis bị restart và mất toàn bộ inventory keys.
            Concert phải đang PUBLISHED. Không thay đổi trạng thái concert.
            """
    )
    @PostMapping("/{id}/reload-inventory")
    public ResponseEntity<ApiResponse<ConcertDetailResponse>> reloadInventory(
            @Parameter(description = "Concert ID") @PathVariable Long id) {

        ConcertDetailResponse response = concertService.reloadInventory(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Đã reload inventory thành công từ DB"));
    }
}
