package com.seatrush.dto;

import java.util.List;

public record CourseResponse(
        Long id,
        String code,
        String title,
        Integer credits,
        List<SectionResponse> sections
) {}
