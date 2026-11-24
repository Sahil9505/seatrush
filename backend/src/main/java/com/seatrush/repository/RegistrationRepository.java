package com.seatrush.repository;

import com.seatrush.entity.Registration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    Optional<Registration> findByStudentIdAndSectionId(Long studentId, Long sectionId);
}
