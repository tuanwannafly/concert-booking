package com.geekup.concertbooking.module.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Một dòng trong booking: loại vé + số lượng.
 *
 * Max 10 vé/lần đặt per category (hard limit ở validation layer).
 * maxPerBooking của TicketCategory là limit ở business layer
 * và có thể nhỏ hơn 10.
 */
public record BookingItemRequest(

    @NotNull(message = "ticketCategoryId không được null")
    Long ticketCategoryId,

    @NotNull(message = "quantity không được null")
    @Min(value = 1, message = "Số lượng tối thiểu là 1")
    @Max(value = 10, message = "Số lượng tối đa là 10 mỗi loại vé")
    Integer quantity

) {}
