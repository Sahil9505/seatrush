package com.seatrush.dto;

import java.time.LocalDateTime;

public record SectionDetailResponse(
        Long sectionId,
        String sectionCode,
        String instructor,
        String schedule,
        Integer capacity,
        Integer seatsTaken,
        Integer seatsLeft,
        LocalDateTime opensAt,
        String courseCode,
        String courseTitle
) {
    public SectionDetailResponse(
            Long sectionId,
            String sectionCode,
            String instructor,
            String schedule,
            Integer capacity,
            Integer seatsTaken,
            LocalDateTime opensAt,
            String courseCode,
            String courseTitle
    ) {
        this(
                sectionId,
                sectionCode,
                instructor,
                schedule,
                capacity,
                seatsTaken,
                (capacity != null && seatsTaken != null) ? (capacity - seatsTaken) : null,
                opensAt,
                courseCode,
                courseTitle
        );
    }
}
