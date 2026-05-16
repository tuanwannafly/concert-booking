package com.geekup.concertbooking.module.concert.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class CreateConcertRequest {

    @NotBlank(message = "Tên concert không được để trống")
    private String name;

    @NotBlank(message = "Địa điểm không được để trống")
    private String venue;

    @NotNull(message = "Ngày tổ chức không được để trống")
    @Future(message = "Ngày tổ chức phải là ngày trong tương lai")
    private LocalDateTime eventDate;

    private String description;

    private String bannerUrl;
}
