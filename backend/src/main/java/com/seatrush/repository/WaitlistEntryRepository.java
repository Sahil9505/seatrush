package com.seatrush.repository;

import com.seatrush.entity.WaitlistEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {
    Optional<WaitlistEntry> findByStudentIdAndSectionId(Long studentId, Long sectionId);
}
