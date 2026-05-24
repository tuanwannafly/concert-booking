package com.geekup.concertbooking.module.concert.dto;

import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ConcertResponse {

    private Long id;
    private String name;
    private String venue;
    private LocalDateTime eventDate;
    private String description;
    private String bannerUrl;
    private ConcertStatus status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    public static ConcertResponse from(Concert concert) {
        return ConcertResponse.builder()
                .id(concert.getId())
                .name(concert.getName())
                .venue(concert.getVenue())
                .eventDate(concert.getEventDate())
                .description(concert.getDescription())
                .bannerUrl(concert.getBannerUrl())
                .status(concert.getStatus())
                .publishedAt(concert.getPublishedAt())
                .createdAt(concert.getCreatedAt())
                .build();
    }
}
