package com.geekup.concertbooking.module.concert.dto;

import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ConcertDetailResponse {

    private Long id;
    private String name;
    private String venue;
    private LocalDateTime eventDate;
    private String description;
    private String bannerUrl;
    private ConcertStatus status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    /** Danh sách loại vé với availableQuantity đã resolved (Redis hoặc DB). */
    private List<TicketCategoryResponse> ticketCategories;

    public static ConcertDetailResponse from(Concert concert, List<TicketCategoryResponse> categories) {
        return ConcertDetailResponse.builder()
                .id(concert.getId())
                .name(concert.getName())
                .venue(concert.getVenue())
                .eventDate(concert.getEventDate())
                .description(concert.getDescription())
                .bannerUrl(concert.getBannerUrl())
                .status(concert.getStatus())
                .publishedAt(concert.getPublishedAt())
                .createdAt(concert.getCreatedAt())
                .ticketCategories(categories)
                .build();
    }
}
