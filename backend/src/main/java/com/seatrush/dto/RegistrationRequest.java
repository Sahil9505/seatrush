package com.seatrush.dto;

import jakarta.validation.constraints.NotNull;

public record RegistrationRequest(
        @NotNull Long sectionId
) {}
