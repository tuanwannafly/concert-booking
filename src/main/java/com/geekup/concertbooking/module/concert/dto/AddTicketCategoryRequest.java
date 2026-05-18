package com.geekup.concertbooking.module.concert.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class AddTicketCategoryRequest {

    @NotBlank(message = "Tên loại vé không được để trống")
    private String name;

    @NotNull(message = "Giá vé không được để trống")
    @Positive(message = "Giá vé phải lớn hơn 0")
    private BigDecimal price;

    @NotNull(message = "Tổng số lượng vé không được để trống")
    @Min(value = 1, message = "Số lượng vé phải ít nhất là 1")
    private Integer totalQuantity;

    @Min(value = 1, message = "Số vé tối đa mỗi booking phải ít nhất là 1")
    private Integer maxPerBooking = 4;
}
