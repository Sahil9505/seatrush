package com.seatrush.repository;

import com.seatrush.entity.TrainStop;
import com.seatrush.entity.TrainStopId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrainStopRepository extends JpaRepository<TrainStop, TrainStopId> {
    List<TrainStop> findByTrainIdOrderByStopIndex(Long trainId);
}
