package com.seatrush.repository;

import com.seatrush.entity.Train;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrainRepository extends JpaRepository<Train, Long> {
    Optional<Train> findByNumber(String number);
}
