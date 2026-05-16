package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.module.concert.dto.ConcertDetailResponse;
import com.geekup.concertbooking.module.concert.dto.ConcertResponse;
import com.geekup.concertbooking.module.concert.dto.TicketCategoryResponse;
import com.geekup.concertbooking.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public endpoints — không cần authentication.
 * Đã khai báo trong SecurityConfig.PUBLIC_ENDPOINTS.
 */
@Tag(name = "Concert", description = "Browse concerts và xem thông tin vé (public)")
@RestController
@RequestMapping("/api/v1/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    @Operation(
        summary = "Danh sách concert đang mở bán",
        description = "Trả về danh sách concert PUBLISHED có phân trang. Mặc định sắp xếp theo eventDate tăng dần."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ConcertResponse>>> listPublishedConcerts(
            @Parameter(description = "Số trang (bắt đầu từ 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Kích thước trang")         @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("eventDate").ascending());
        Page<ConcertResponse> result = concertService.listPublishedConcerts(pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Chi tiết concert",
        description = "Trả về thông tin concert PUBLISHED kèm danh sách loại vé. " +
                      "availableQuantity phản ánh tồn kho real-time từ Redis."
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConcertDetailResponse>> getConcertDetail(
            @Parameter(description = "Concert ID") @PathVariable Long id) {

        ConcertDetailResponse response = concertService.getConcertDetail(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Danh sách loại vé",
        description = "Trả về các loại vé của concert PUBLISHED kèm số lượng tồn kho real-time."
    )
    @GetMapping("/{id}/categories")
    public ResponseEntity<ApiResponse<List<TicketCategoryResponse>>> getTicketCategories(
            @Parameter(description = "Concert ID") @PathVariable Long id) {

        List<TicketCategoryResponse> categories = concertService.getTicketCategories(id);
        return ResponseEntity.ok(ApiResponse.success(categories));
    }
}
