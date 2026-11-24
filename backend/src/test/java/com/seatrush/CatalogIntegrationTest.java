package com.seatrush;

import com.seatrush.dto.AuthResponse;
import com.seatrush.dto.CourseResponse;
import com.seatrush.dto.ErrorResponse;
import com.seatrush.dto.LoginRequest;
import com.seatrush.dto.SectionDetailResponse;
import com.seatrush.dto.SectionResponse;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.jpa.open-in-view=false"
        }
)
@ActiveProfiles("dev")
class CatalogIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String token;

    @BeforeEach
    void setUp() {
        ResponseEntity<AuthResponse> loginResp = rest.postForEntity(
                "/api/auth/login",
                new LoginRequest("admin@seatrush.test", "password123"),
                AuthResponse.class
        );
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).isNotNull();
        token = loginResp.getBody().token();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("No token returns 401 for both /api/courses and /api/sections/{id}")
    void no_token_returns_401() {
        ResponseEntity<Void> coursesResp = rest.getForEntity("/api/courses", Void.class);
        assertThat(coursesResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> sectionResp = rest.getForEntity("/api/sections/1", Void.class);
        assertThat(sectionResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/courses returns 20 courses and 50 sections with correct seatsLeft and executes 1 query")
    void get_courses_returns_20_courses_50_sections_and_no_n_plus_one() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        ResponseEntity<List<CourseResponse>> resp = rest.exchange(
                "/api/courses",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<List<CourseResponse>>() {}
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<CourseResponse> courses = resp.getBody();
        assertThat(courses).isNotNull().hasSize(20);

        int totalSections = courses.stream()
                .mapToInt(c -> c.sections().size())
                .sum();
        assertThat(totalSections).isEqualTo(50);

        for (CourseResponse course : courses) {
            assertThat(course.id()).isNotNull();
            assertThat(course.code()).isNotBlank();
            assertThat(course.title()).isNotBlank();
            for (SectionResponse sec : course.sections()) {
                assertThat(sec.sectionId()).isNotNull();
                assertThat(sec.sectionCode()).isNotBlank();
                assertThat(sec.capacity()).isNotNull().isGreaterThan(0);
                assertThat(sec.seatsTaken()).isNotNull().isGreaterThanOrEqualTo(0);
                assertThat(sec.seatsLeft()).isEqualTo(sec.capacity() - sec.seatsTaken());
                assertThat(sec.opensAt()).isNotNull();
            }
        }

        // Single query for loading courses with sections — strictly no N+1
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/sections/{id} returns single section with course fields and correct seatsLeft")
    void get_section_by_id_success() {
        // First retrieve list of courses to pick an existing section ID
        ResponseEntity<List<CourseResponse>> listResp = rest.exchange(
                "/api/courses",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<List<CourseResponse>>() {}
        );
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotEmpty();

        CourseResponse firstCourse = listResp.getBody().getFirst();
        assertThat(firstCourse.sections()).isNotEmpty();
        SectionResponse firstSection = firstCourse.sections().getFirst();

        ResponseEntity<SectionDetailResponse> resp = rest.exchange(
                "/api/sections/" + firstSection.sectionId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                SectionDetailResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SectionDetailResponse section = resp.getBody();
        assertThat(section).isNotNull();
        assertThat(section.sectionId()).isEqualTo(firstSection.sectionId());
        assertThat(section.sectionCode()).isEqualTo(firstSection.sectionCode());
        assertThat(section.capacity()).isEqualTo(firstSection.capacity());
        assertThat(section.seatsTaken()).isEqualTo(firstSection.seatsTaken());
        assertThat(section.seatsLeft()).isEqualTo(firstSection.seatsLeft());
        assertThat(section.courseCode()).isEqualTo(firstCourse.code());
        assertThat(section.courseTitle()).isEqualTo(firstCourse.title());
    }

    @Test
    @DisplayName("GET /api/sections/{id} with unknown id returns 404 NOT_FOUND")
    void get_section_unknown_id_returns_404() {
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                "/api/sections/999999",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                ErrorResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
    }
}
