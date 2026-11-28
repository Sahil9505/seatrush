package com.seatrush.dto;

import com.seatrush.entity.BookingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingResponse(
    Long bookingId,
    String pnr,
    BookingStatus status,
    Long seatId,
    String coach,
    Integer seatNo,
    LocalDateTime holdExpiresAt,
    BigDecimal price
) {}
