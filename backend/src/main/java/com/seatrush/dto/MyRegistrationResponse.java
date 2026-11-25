package com.seatrush.dto;

import com.seatrush.entity.RegistrationStatus;

public record MyRegistrationResponse(
        Long sectionId,
        String courseCode,
        String sectionCode,
        String status
) {
    public MyRegistrationResponse(Long sectionId, String courseCode, String sectionCode, RegistrationStatus status) {
        this(sectionId, courseCode, sectionCode, status != null ? status.name() : null);
    }
}
