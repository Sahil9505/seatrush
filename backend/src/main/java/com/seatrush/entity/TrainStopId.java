package com.seatrush.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrainStopId implements Serializable {
    private Long trainId;
    private Integer stopIndex;
}
