package com.seatrush.repository;

import com.seatrush.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SectionRepository extends JpaRepository<Section, Long> {

    @Modifying
    @Query(value = "UPDATE sections SET seats_taken = seats_taken + 1 WHERE id = :id AND seats_taken < capacity", nativeQuery = true)
    int takeSeat(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE sections SET seats_taken = seats_taken - 1 WHERE id = :id AND seats_taken > 0", nativeQuery = true)
    int releaseSeat(@Param("id") Long id);
}
