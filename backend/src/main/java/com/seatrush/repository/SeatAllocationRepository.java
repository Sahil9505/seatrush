package com.seatrush.repository;

import com.seatrush.entity.SeatAllocation;
import com.seatrush.entity.SeatAllocationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatAllocationRepository extends JpaRepository<SeatAllocation, SeatAllocationId> {
}
