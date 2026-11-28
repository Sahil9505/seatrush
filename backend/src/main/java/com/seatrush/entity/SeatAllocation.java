package com.seatrush.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seat_allocations")
@IdClass(SeatAllocationId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatAllocation {

    @Id
    @Column(name = "journey_id")
    private Long journeyId;

    @Id
    @Column(name = "seat_id")
    private Long seatId;

    @Id
    @Column(name = "segment_index")
    private Integer segmentIndex;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
}
