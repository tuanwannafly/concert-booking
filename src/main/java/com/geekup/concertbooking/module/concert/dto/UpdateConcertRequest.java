package com.geekup.concertbooking.module.concert.dto;

import jakarta.validation.constraints.Future;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tất cả field là nullable → chỉ update field nào client gửi lên (partial update).
 * Service sẽ check null trước khi set.
 */
@Getter
@NoArgsConstructor
public class UpdateConcertRequest {

    private String name;

    private String venue;

    @Future(message = "Ngày tổ chức phải là ngày trong tương lai")
    private LocalDateTime eventDate;

    private String description;

    private String bannerUrl;
}
