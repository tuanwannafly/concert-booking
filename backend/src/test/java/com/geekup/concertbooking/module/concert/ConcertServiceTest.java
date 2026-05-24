package com.geekup.concertbooking.module.concert;

import com.geekup.concertbooking.config.AppProperties;
import com.geekup.concertbooking.entity.Concert;
import com.geekup.concertbooking.entity.TicketCategory;
import com.geekup.concertbooking.module.concert.dto.AddTicketCategoryRequest;
import com.geekup.concertbooking.module.concert.dto.ConcertDetailResponse;
import com.geekup.concertbooking.module.concert.dto.TicketCategoryResponse;
import com.geekup.concertbooking.shared.enums.ConcertStatus;
import com.geekup.concertbooking.shared.exception.AppException;
import com.geekup.concertbooking.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcertServiceTest {

    @Mock private ConcertRepository            concertRepository;
    @Mock private TicketCategoryRepository     ticketCategoryRepository;
    @Mock private RedisTemplate<String, String> stringRedisTemplate;
    @Mock private AppProperties                appProperties;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ConcertService concertService;

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Concert draftConcert;
    private Concert publishedConcert;
    private TicketCategory vipCategory;
    private TicketCategory stdCategory;

    @BeforeEach
    void setUp() {
        draftConcert = Concert.builder()
            .id(1L)
            .name("Rock Festival")
            .venue("Stadium")
            .eventDate(LocalDateTime.now().plusDays(30))
            .status(ConcertStatus.DRAFT)
            .build();

        publishedConcert = Concert.builder()
            .id(2L)
            .name("Jazz Night")
            .venue("Arena")
            .eventDate(LocalDateTime.now().plusDays(15))
            .status(ConcertStatus.PUBLISHED)
            .publishedAt(LocalDateTime.now().minusDays(1))
            .build();

        vipCategory = TicketCategory.builder()
            .id(10L)
            .concert(draftConcert)
            .name("VIP")
            .price(BigDecimal.valueOf(1_000_000))
            .totalQuantity(100)
            .availableQuantity(100)
            .maxPerBooking(4)
            .build();

        stdCategory = TicketCategory.builder()
            .id(11L)
            .concert(draftConcert)
            .name("Standard")
            .price(BigDecimal.valueOf(500_000))
            .totalQuantity(500)
            .availableQuantity(500)
            .maxPerBooking(4)
            .build();

        // Stub Redis prefix helper used by all inventory operations
        AppProperties.RedisProperties redisProps = mock(AppProperties.RedisProperties.class);
        lenient().when(appProperties.getRedis()).thenReturn(redisProps);
        lenient().when(redisProps.getInventoryKeyPrefix()).thenReturn("inventory:ticket:");
    }

    // =========================================================================
    //  publishConcert
    // =========================================================================

    @Test
    void publishConcert_draftWithCategories_changesStatusAndLoadsRedis() {
        given(concertRepository.findById(1L)).willReturn(Optional.of(draftConcert));
        given(ticketCategoryRepository.findByConcertId(1L)).willReturn(List.of(vipCategory, stdCategory));
        given(concertRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn("100", "500"); // for resolveCategories

        ConcertDetailResponse response = concertService.publishConcert(1L);

        assertThat(response).isNotNull();
        // Redis SET must be called once per category
        verify(valueOperations, times(2)).set(anyString(), anyString());
        // inventory:ticket:10 and inventory:ticket:11 must be seeded
        verify(valueOperations).set("inventory:ticket:10", "100");
        verify(valueOperations).set("inventory:ticket:11", "500");
    }

    @Test
    void publishConcert_concertNotFound_throwsAppException() {
        given(concertRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> concertService.publishConcert(99L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.CONCERT_NOT_FOUND);
    }

    @Test
    void publishConcert_alreadyPublished_throwsInvalidConcertStatus() {
        given(concertRepository.findById(2L)).willReturn(Optional.of(publishedConcert));

        assertThatThrownBy(() -> concertService.publishConcert(2L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CONCERT_STATUS);
    }

    @Test
    void publishConcert_noCategories_throwsInvalidConcertStatus() {
        given(concertRepository.findById(1L)).willReturn(Optional.of(draftConcert));
        given(ticketCategoryRepository.findByConcertId(1L)).willReturn(Collections.emptyList());

        assertThatThrownBy(() -> concertService.publishConcert(1L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CONCERT_STATUS);

        // Redis must NOT be touched when validation fails
        verifyNoInteractions(stringRedisTemplate);
    }

    // =========================================================================
    //  reloadInventory
    // =========================================================================

    @Test
    void reloadInventory_publishedConcert_reSeedsAllCategoriesFromDb() {
        TicketCategory vipPub = TicketCategory.builder()
            .id(20L).concert(publishedConcert).name("VIP")
            .price(BigDecimal.valueOf(1_000_000))
            .totalQuantity(100).availableQuantity(87).maxPerBooking(4).build();
        TicketCategory stdPub = TicketCategory.builder()
            .id(21L).concert(publishedConcert).name("Standard")
            .price(BigDecimal.valueOf(500_000))
            .totalQuantity(500).availableQuantity(312).maxPerBooking(4).build();

        given(concertRepository.findById(2L)).willReturn(Optional.of(publishedConcert));
        given(ticketCategoryRepository.findByConcertId(2L)).willReturn(List.of(vipPub, stdPub));
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);

        ConcertDetailResponse response = concertService.reloadInventory(2L);

        assertThat(response).isNotNull();
        // Must seed exact DB values, not stale Redis values
        verify(valueOperations).set("inventory:ticket:20", "87");
        verify(valueOperations).set("inventory:ticket:21", "312");
    }

    @Test
    void reloadInventory_draftConcert_throwsInvalidConcertStatus() {
        given(concertRepository.findById(1L)).willReturn(Optional.of(draftConcert));

        assertThatThrownBy(() -> concertService.reloadInventory(1L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CONCERT_STATUS);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void reloadInventory_concertNotFound_throwsAppException() {
        given(concertRepository.findById(55L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> concertService.reloadInventory(55L))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.CONCERT_NOT_FOUND);
    }

    // =========================================================================
    //  addTicketCategory
    // =========================================================================

    @Test
    void addTicketCategory_draftConcert_persistsAndReturnsCategoryResponse() {
        AddTicketCategoryRequest request = mock(AddTicketCategoryRequest.class);
        given(request.getName()).willReturn("VVIP");
        given(request.getPrice()).willReturn(BigDecimal.valueOf(2_000_000));
        given(request.getTotalQuantity()).willReturn(50);
        given(request.getMaxPerBooking()).willReturn(2);

        TicketCategory saved = TicketCategory.builder()
            .id(30L).concert(draftConcert).name("VVIP")
            .price(BigDecimal.valueOf(2_000_000))
            .totalQuantity(50).availableQuantity(50).maxPerBooking(2).build();

        given(concertRepository.findById(1L)).willReturn(Optional.of(draftConcert));
        given(ticketCategoryRepository.save(any())).willReturn(saved);

        TicketCategoryResponse response = concertService.addTicketCategory(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("VVIP");
        assertThat(response.getTotalQuantity()).isEqualTo(50);
    }

    @Test
    void addTicketCategory_publishedConcert_throwsInvalidConcertStatus() {
        given(concertRepository.findById(2L)).willReturn(Optional.of(publishedConcert));

        assertThatThrownBy(() -> concertService.addTicketCategory(2L, mock(AddTicketCategoryRequest.class)))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getErrorCode())
            .isEqualTo(ErrorCode.INVALID_CONCERT_STATUS);

        verify(ticketCategoryRepository, never()).save(any());
    }
}