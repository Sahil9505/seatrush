package com.seatrush;

import com.seatrush.dto.DropResponse;
import com.seatrush.dto.ErrorResponse;
import com.seatrush.dto.RegistrationRequest;
import com.seatrush.dto.RegistrationResponse;
import com.seatrush.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DropIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private Long studentId;
    private String studentToken;
    private Long courseId;
    private final List<Long> createdSectionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM registrations");
        jdbcTemplate.update("DELETE FROM sections");
        jdbcTemplate.update("DELETE FROM courses");
        jdbcTemplate.update("DELETE FROM students");

        jdbcTemplate.update(
                "INSERT INTO students (roll_no, name, email, password_hash, role) " +
                        "VALUES ('DROP001', 'Drop Student', 'drop.student@test.com', 'hash123', 'STUDENT')"
        );
        studentId = jdbcTemplate.queryForObject(
                "SELECT id FROM students WHERE email = 'drop.student@test.com'", Long.class);
        studentToken = jwtService.generateToken(studentId, "STUDENT");

        jdbcTemplate.update(
                "INSERT INTO courses (code, title, credits) VALUES ('CS-DROP', 'Drop Testing Course', 3)"
        );
        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM courses WHERE code = 'CS-DROP'", Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM registrations");
        jdbcTemplate.update("DELETE FROM sections");
        jdbcTemplate.update("DELETE FROM courses");
        jdbcTemplate.update("DELETE FROM students");
        createdSectionIds.clear();
    }

    private HttpHeaders studentHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set("Idempotency-Key", "test-idem-drop-" + System.nanoTime());
        return headers;
    }

    private Long createSection(int capacity, int seatsTaken) {
        String secCode = "D" + (System.nanoTime() % 1000000);
        jdbcTemplate.update(
                "INSERT INTO sections (course_id, section_code, instructor, schedule, capacity, seats_taken, opens_at) " +
                        "VALUES (?, ?, 'Dr. Dropper', 'MWF 11:00-12:00', ?, ?, ?)",
                courseId, secCode, capacity, seatsTaken, Timestamp.from(Instant.now().minusSeconds(3600))
        );
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM sections WHERE course_id = ? AND section_code = ?",
                Long.class, courseId, secCode
        );
        createdSectionIds.add(id);
        return id;
    }

    @Test
    @DisplayName("No token returns 401")
    void no_token_returns_401() {
        Long sectionId = createSection(10, 0);

        ResponseEntity<Void> resp = rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()),
                Void.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Unknown section returns 404 NOT_FOUND")
    void unknown_section_returns_404() {
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                "/api/registrations/999999",
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                ErrorResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Student not registered returns 404 NOT_FOUND")
    void not_registered_returns_404() {
        Long sectionId = createSection(10, 0);

        ResponseEntity<ErrorResponse> resp = rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                ErrorResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Drop frees seat: seatsTaken back to 0 and registration marked DROPPED")
    void drop_frees_seat() {
        Long sectionId = createSection(10, 0);

        // Register first
        ResponseEntity<RegistrationResponse> regResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(studentToken)),
                RegistrationResponse.class
        );
        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Integer seatsAfterReg = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfterReg).isEqualTo(1);

        // Drop
        ResponseEntity<DropResponse> dropResp = rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                DropResponse.class
        );

        assertThat(dropResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dropResp.getBody()).isNotNull();
        assertThat(dropResp.getBody().sectionId()).isEqualTo(sectionId);
        assertThat(dropResp.getBody().status()).isEqualTo("DROPPED");

        Integer seatsAfterDrop = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfterDrop).isEqualTo(0);

        String regStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM registrations WHERE student_id = ? AND section_id = ?",
                String.class, studentId, sectionId);
        assertThat(regStatus).isEqualTo("DROPPED");
    }

    @Test
    @DisplayName("Dropping twice: second returns 404 NOT_FOUND")
    void dropping_twice_returns_404() {
        Long sectionId = createSection(10, 0);

        // Register
        rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(studentToken)),
                RegistrationResponse.class
        );

        // First drop -> 200 OK
        ResponseEntity<DropResponse> firstDrop = rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                DropResponse.class
        );
        assertThat(firstDrop.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second drop -> 404 NOT_FOUND
        ResponseEntity<ErrorResponse> secondDrop = rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                ErrorResponse.class
        );
        assertThat(secondDrop.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(secondDrop.getBody()).isNotNull();
        assertThat(secondDrop.getBody().code()).isEqualTo("NOT_FOUND");

        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(0);
    }

    @Test
    @DisplayName("Drop then re-register succeeds via row reactivation")
    void drop_then_reregister_succeeds_via_row_reactivation() {
        Long sectionId = createSection(10, 0);

        // Initial register
        rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(studentToken)),
                RegistrationResponse.class
        );

        // Drop
        rest.exchange(
                "/api/registrations/" + sectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(studentHeaders(studentToken)),
                DropResponse.class
        );

        // Re-register
        ResponseEntity<RegistrationResponse> reRegResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(studentToken)),
                RegistrationResponse.class
        );

        assertThat(reRegResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reRegResp.getBody()).isNotNull();
        assertThat(reRegResp.getBody().status()).isEqualTo("CONFIRMED");

        // Verify seats_taken is back to 1
        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(1);

        // Verify exactly 1 registration row exists and status is CONFIRMED (reactivated)
        Integer regCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE student_id = ? AND section_id = ?",
                Integer.class, studentId, sectionId);
        assertThat(regCount).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM registrations WHERE student_id = ? AND section_id = ?",
                String.class, studentId, sectionId);
        assertThat(status).isEqualTo("CONFIRMED");
    }
}
