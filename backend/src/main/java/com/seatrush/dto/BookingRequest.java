package com.seatrush.dto;

import jakarta.validation.constraints.NotNull;

public record BookingRequest(
    @NotNull(message = "journeyId is required")
    Long journeyId,

    @NotNull(message = "fromStopIndex is required")
    Integer fromStopIndex,

    @NotNull(message = "toStopIndex is required")
    Integer toStopIndex
) {}
