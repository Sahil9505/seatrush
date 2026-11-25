package com.seatrush.dto;

import com.seatrush.entity.RegistrationStatus;

public record RegistrationResponse(
        Long id,
        Long sectionId,
        String status
) {
    public RegistrationResponse(Long id, Long sectionId, RegistrationStatus status) {
        this(id, sectionId, status != null ? status.name() : null);
    }
}
