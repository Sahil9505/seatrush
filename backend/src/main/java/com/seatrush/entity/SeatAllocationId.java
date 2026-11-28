package com.seatrush.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatAllocationId implements Serializable {
    private Long journeyId;
    private Long seatId;
    private Integer segmentIndex;
}
