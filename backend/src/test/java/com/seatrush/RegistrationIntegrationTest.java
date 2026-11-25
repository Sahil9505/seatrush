package com.seatrush;

import com.seatrush.dto.ErrorResponse;
import com.seatrush.dto.MyRegistrationResponse;
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
import org.springframework.core.ParameterizedTypeReference;
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
@ActiveProfiles("dev")
class RegistrationIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private Long testCourseId;
    private Long student1Id;
    private Long student2Id;
    private String student1Token;
    private String student2Token;
    private final List<Long> createdSectionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Query two seeded students
        List<Long> studentIds = jdbcTemplate.queryForList(
                "SELECT id FROM students WHERE role = 'STUDENT' ORDER BY id ASC LIMIT 2", Long.class);
        assertThat(studentIds).hasSize(2);
        student1Id = studentIds.get(0);
        student2Id = studentIds.get(1);

        student1Token = jwtService.generateToken(student1Id, "STUDENT");
        student2Token = jwtService.generateToken(student2Id, "STUDENT");

        // Ensure a test course exists
        List<Long> courseIds = jdbcTemplate.queryForList(
                "SELECT id FROM courses ORDER BY id ASC LIMIT 1", Long.class);
        assertThat(courseIds).isNotEmpty();
        testCourseId = courseIds.getFirst();
    }

    @AfterEach
    void tearDown() {
        for (Long sectionId : createdSectionIds) {
            jdbcTemplate.update("DELETE FROM registrations WHERE section_id = ?", sectionId);
            jdbcTemplate.update("DELETE FROM sections WHERE id = ?", sectionId);
        }
        createdSectionIds.clear();
    }

    private HttpHeaders studentHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "test-idem-key-" + System.nanoTime());
        return headers;
    }

    private Long createSection(int capacity, int seatsTaken) {
        String secCode = "T" + (System.nanoTime() % 1000000);
        jdbcTemplate.update(
                "INSERT INTO sections (course_id, section_code, instructor, schedule, capacity, seats_taken, opens_at) " +
                        "VALUES (?, ?, 'Dr. Test', 'MWF 09:00-10:00', ?, ?, ?)",
                testCourseId, secCode, capacity, seatsTaken, Timestamp.from(Instant.now().minusSeconds(3600))
        );
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM sections WHERE course_id = ? AND section_code = ?",
                Long.class, testCourseId, secCode
        );
        createdSectionIds.add(id);
        return id;
    }

    @Test
    @DisplayName("No token returns 401")
    void no_token_returns_401() {
        ResponseEntity<Void> resp = rest.postForEntity(
                "/api/registrations",
                new RegistrationRequest(1L),
                Void.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Unknown section returns 404 NOT_FOUND")
    void unknown_section_returns_404() {
        ResponseEntity<ErrorResponse> resp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(999999L), studentHeaders(student1Token)),
                ErrorResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Success: student registers, seatsTaken becomes 1, and shows in /api/registrations/me")
    void register_success_increments_seats_taken() {
        Long sectionId = createSection(10, 0);

        ResponseEntity<RegistrationResponse> resp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                RegistrationResponse.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().sectionId()).isEqualTo(sectionId);
        assertThat(resp.getBody().status()).isEqualTo("CONFIRMED");

        // Verify DB counter
        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(1);

        // Verify GET /api/registrations/me
        ResponseEntity<List<MyRegistrationResponse>> myRegsResp = rest.exchange(
                "/api/registrations/me",
                HttpMethod.GET,
                new HttpEntity<>(studentHeaders(student1Token)),
                new ParameterizedTypeReference<List<MyRegistrationResponse>>() {}
        );

        assertThat(myRegsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(myRegsResp.getBody()).isNotNull();
        boolean found = myRegsResp.getBody().stream()
                .anyMatch(r -> r.sectionId().equals(sectionId) && "CONFIRMED".equals(r.status()));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("Duplicate register returns 409 ALREADY_REGISTERED")
    void duplicate_register_returns_409() {
        Long sectionId = createSection(10, 0);

        // First registration
        ResponseEntity<RegistrationResponse> firstResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                RegistrationResponse.class
        );
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Duplicate registration
        ResponseEntity<ErrorResponse> dupResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                ErrorResponse.class
        );

        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dupResp.getBody()).isNotNull();
        assertThat(dupResp.getBody().code()).isEqualTo("ALREADY_REGISTERED");

        // Counter remains 1
        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(1);
    }

    @Test
    @DisplayName("Full section (capacity 1, second student) returns 409 SECTION_FULL and seatsTaken stays 1 with no extra row")
    void full_section_returns_409_and_rolls_back() {
        Long sectionId = createSection(1, 0);

        // First student registers -> success
        ResponseEntity<RegistrationResponse> firstResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                RegistrationResponse.class
        );
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Integer seatsAfterFirst = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfterFirst).isEqualTo(1);

        // Second student registers -> 409 SECTION_FULL
        ResponseEntity<ErrorResponse> secondResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student2Token)),
                ErrorResponse.class
        );

        assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResp.getBody()).isNotNull();
        assertThat(secondResp.getBody().code()).isEqualTo("SECTION_FULL");

        // Verify seatsTaken stays 1
        Integer seatsAfterSecond = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfterSecond).isEqualTo(1);

        // Verify exactly 1 registration row exists in DB (for student1, rolled back for student2)
        Integer regCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE section_id = ?", Integer.class, sectionId);
        assertThat(regCount).isEqualTo(1);

        Integer student2RegCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE section_id = ? AND student_id = ?",
                Integer.class, sectionId, student2Id);
        assertThat(student2RegCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Re-registering after a manual DROPPED row reactivates it")
    void reregister_after_manual_dropped_reactivates_row() {
        Long sectionId = createSection(5, 0);

        // Student 1 registers
        ResponseEntity<RegistrationResponse> firstResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                RegistrationResponse.class
        );
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long regId = jdbcTemplate.queryForObject(
                "SELECT id FROM registrations WHERE student_id = ? AND section_id = ?",
                Long.class, student1Id, sectionId);
        assertThat(regId).isNotNull();

        // Manually drop registration and decrement seat
        jdbcTemplate.update("UPDATE registrations SET status = 'DROPPED' WHERE id = ?", regId);
        jdbcTemplate.update("UPDATE sections SET seats_taken = seats_taken - 1 WHERE id = ?", sectionId);

        Integer seatsBeforeReReg = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsBeforeReReg).isEqualTo(0);

        // Re-register
        ResponseEntity<RegistrationResponse> reRegResp = rest.exchange(
                "/api/registrations",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(sectionId), studentHeaders(student1Token)),
                RegistrationResponse.class
        );

        assertThat(reRegResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reRegResp.getBody()).isNotNull();
        assertThat(reRegResp.getBody().id()).isEqualTo(regId);
        assertThat(reRegResp.getBody().status()).isEqualTo("CONFIRMED");

        // Status is reactivated to CONFIRMED
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM registrations WHERE id = ?", String.class, regId);
        assertThat(currentStatus).isEqualTo("CONFIRMED");

        // Total rows for (student, section) is still exactly 1
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE student_id = ? AND section_id = ?",
                Integer.class, student1Id, sectionId);
        assertThat(totalRows).isEqualTo(1);

        // seats_taken is back to 1
        Integer seatsAfterReReg = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfterReReg).isEqualTo(1);
    }
}
