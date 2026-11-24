package com.seatrush.controller;

import com.seatrush.dto.CourseResponse;
import com.seatrush.dto.SectionDetailResponse;
import com.seatrush.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping("/courses")
    public ResponseEntity<List<CourseResponse>> getCourses() {
        return ResponseEntity.ok(courseService.getAllCoursesWithSections());
    }

    @GetMapping("/sections/{id}")
    public ResponseEntity<SectionDetailResponse> getSection(@PathVariable Long id) {
        return ResponseEntity.ok(courseService.getSectionById(id));
    }
}
