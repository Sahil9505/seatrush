package com.seatrush.service;

import com.seatrush.dto.CourseResponse;
import com.seatrush.dto.CourseSectionRow;
import com.seatrush.dto.SectionDetailResponse;
import com.seatrush.dto.SectionResponse;
import com.seatrush.entity.Section;
import com.seatrush.exception.AppException;
import com.seatrush.repository.CourseRepository;
import com.seatrush.repository.SectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final SectionRepository sectionRepository;

    public List<CourseResponse> getAllCoursesWithSections() {
        List<CourseSectionRow> rows = courseRepository.findAllWithSections();
        Map<Long, CourseBuilder> map = new LinkedHashMap<>();

        for (CourseSectionRow row : rows) {
            CourseBuilder builder = map.computeIfAbsent(
                    row.courseId(),
                    id -> new CourseBuilder(row.courseId(), row.courseCode(), row.courseTitle(), row.credits())
            );

            if (row.sectionId() != null) {
                builder.sections.add(new SectionResponse(
                        row.sectionId(),
                        row.sectionCode(),
                        row.instructor(),
                        row.schedule(),
                        row.capacity(),
                        row.seatsTaken(),
                        row.opensAt()
                ));
            }
        }

        List<CourseResponse> result = new ArrayList<>(map.size());
        for (CourseBuilder builder : map.values()) {
            result.add(new CourseResponse(
                    builder.id,
                    builder.code,
                    builder.title,
                    builder.credits,
                    builder.sections
            ));
        }
        return result;
    }

    public SectionDetailResponse getSectionById(Long id) {
        Section section = sectionRepository.findByIdWithCourse(id)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Section not found with id: " + id, HttpStatus.NOT_FOUND));

        return new SectionDetailResponse(
                section.getId(),
                section.getSectionCode(),
                section.getInstructor(),
                section.getSchedule(),
                section.getCapacity(),
                section.getSeatsTaken(),
                section.getOpensAt(),
                section.getCourse().getCode(),
                section.getCourse().getTitle()
        );
    }

    private static class CourseBuilder {
        final Long id;
        final String code;
        final String title;
        final Integer credits;
        final List<SectionResponse> sections = new ArrayList<>();

        CourseBuilder(Long id, String code, String title, Integer credits) {
            this.id = id;
            this.code = code;
            this.title = title;
            this.credits = credits;
        }
    }
}
