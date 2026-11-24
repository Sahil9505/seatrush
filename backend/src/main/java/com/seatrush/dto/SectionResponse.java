package com.seatrush.dto;

import java.time.LocalDateTime;

public record SectionResponse(
        Long sectionId,
        String sectionCode,
        String instructor,
        String schedule,
        Integer capacity,
        Integer seatsTaken,
        Integer seatsLeft,
        LocalDateTime opensAt
) {
    public SectionResponse(
            Long sectionId,
            String sectionCode,
            String instructor,
            String schedule,
            Integer capacity,
            Integer seatsTaken,
            LocalDateTime opensAt
    ) {
        this(
                sectionId,
                sectionCode,
                instructor,
                schedule,
                capacity,
                seatsTaken,
                (capacity != null && seatsTaken != null) ? (capacity - seatsTaken) : null,
                opensAt
        );
    }
}
