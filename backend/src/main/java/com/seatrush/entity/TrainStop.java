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
@Table(name = "train_stops")
@IdClass(TrainStopId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrainStop {

    @Id
    @Column(name = "train_id")
    private Long trainId;

    @Id
    @Column(name = "stop_index")
    private Integer stopIndex;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "arrival_offset_min")
    private Integer arrivalOffsetMin;

    @Column(name = "departure_offset_min")
    private Integer departureOffsetMin;

    @Column(name = "distance_km", nullable = false)
    @Builder.Default
    private Integer distanceKm = 0;
}
