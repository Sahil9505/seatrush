package com.seatrush.repository;

import com.seatrush.dto.CourseSectionRow;
import com.seatrush.entity.Course;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    Optional<Course> findByCode(String code);

    @Query("SELECT new com.seatrush.dto.CourseSectionRow(" +
            "c.id, c.code, c.title, c.credits, " +
            "s.id, s.sectionCode, s.instructor, s.schedule, s.capacity, s.seatsTaken, s.opensAt) " +
            "FROM Course c LEFT JOIN Section s ON s.course = c " +
            "ORDER BY c.id ASC, s.id ASC")
    List<CourseSectionRow> findAllWithSections();
}
