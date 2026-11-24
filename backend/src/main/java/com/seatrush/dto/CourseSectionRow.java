package com.seatrush.dto;

import java.time.LocalDateTime;

public record CourseSectionRow(
        Long courseId,
        String courseCode,
        String courseTitle,
        Integer credits,
        Long sectionId,
        String sectionCode,
        String instructor,
        String schedule,
        Integer capacity,
        Integer seatsTaken,
        LocalDateTime opensAt
) {}
