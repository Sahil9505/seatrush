package com.seatrush;

import com.seatrush.dto.BookingRequest;
import com.seatrush.dto.BookingResponse;
import com.seatrush.dto.ErrorResponse;
import com.seatrush.entity.BookingStatus;
import com.seatrush.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BookingFunctionalTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private Long journeyId;
    private Long trainId;
    private final List<Long> stationIds = new ArrayList<>();
    private final List<Long> seatIds = new ArrayList<>();
    private Long user1Id;
    private Long user2Id;
    private String user1Token;
    private String user2Token;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seat_allocations");
        jdbcTemplate.update("DELETE FROM bookings");
        jdbcTemplate.update("DELETE FROM journeys");
        jdbcTemplate.update("DELETE FROM seats");
        jdbcTemplate.update("DELETE FROM train_stops");
        jdbcTemplate.update("DELETE FROM trains");
        jdbcTemplate.update("DELETE FROM stations");
        jdbcTemplate.update("DELETE FROM users");

        stationIds.clear();
        seatIds.clear();

        for (int i = 0; i < 6; i++) {
            KeyHolder kh = new GeneratedKeyHolder();
            final int idx = i;
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stations (code, name, city) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, "ST" + idx);
                ps.setString(2, "Station-" + idx);
                ps.setString(3, "City-" + idx);
                return ps;
            }, kh);
            stationIds.add(extractId(kh));
        }

        KeyHolder trainKh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO trains (number, name) VALUES ('TR101', 'Test Express')",
                Statement.RETURN_GENERATED_KEYS
            );
            return ps;
        }, trainKh);
        trainId = extractId(trainKh);

        for (int i = 0; i < 6; i++) {
            jdbcTemplate.update(
                "INSERT INTO train_stops (train_id, stop_index, station_id, arrival_offset_min, departure_offset_min, distance_km) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                trainId, i, stationIds.get(i), i * 30, i * 30 + 5, i * 50
            );
        }

        for (int i = 1; i <= 2; i++) {
            KeyHolder seatKh = new GeneratedKeyHolder();
            final int seatNo = i;
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO seats (train_id, coach, seat_no, seat_type) VALUES (?, 'C1', ?, 'STANDARD')",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setLong(1, trainId);
                ps.setInt(2, seatNo);
                return ps;
            }, seatKh);
            seatIds.add(extractId(seatKh));
        }

        KeyHolder journeyKh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO journeys (train_id, travel_date, booking_opens_at, status) " +
                "VALUES (?, CURRENT_DATE(), ?, 'OPEN')",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, trainId);
            ps.setTimestamp(2, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1)));
            return ps;
        }, journeyKh);
        journeyId = extractId(journeyKh);

        KeyHolder u1Kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO users (name, email, password_hash, role) VALUES ('User 1', 'u1@example.com', 'hash', 'USER')",
                Statement.RETURN_GENERATED_KEYS
            );
            return ps;
        }, u1Kh);
        user1Id = extractId(u1Kh);

        KeyHolder u2Kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO users (name, email, password_hash, role) VALUES ('User 2', 'u2@example.com', 'hash', 'USER')",
                Statement.RETURN_GENERATED_KEYS
            );
            return ps;
        }, u2Kh);
        user2Id = extractId(u2Kh);

        user1Token = jwtService.generateToken(user1Id, "USER");
        user2Token = jwtService.generateToken(user2Id, "USER");
    }

    private long extractId(KeyHolder keyHolder) {
        if (!keyHolder.getKeyList().isEmpty()) {
            java.util.Map<String, Object> keys = keyHolder.getKeyList().get(0);
            for (java.util.Map.Entry<String, Object> entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        throw new IllegalStateException("Failed to extract generated ID");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @Test
    void noToken_returns401() {
        HttpEntity<BookingRequest> entity = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(null)
        );
        ResponseEntity<Void> resp = rest.postForEntity("/api/bookings", entity, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownJourney_returns404() {
        HttpEntity<BookingRequest> entity = new HttpEntity<>(
            new BookingRequest(999999L, 0, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp = rest.postForEntity("/api/bookings", entity, ErrorResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("JOURNEY_NOT_FOUND");
    }

    @Test
    void badStops_returns400() {
        HttpEntity<BookingRequest> entity1 = new HttpEntity<>(
            new BookingRequest(journeyId, 2, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp1 = rest.postForEntity("/api/bookings", entity1, ErrorResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp1.getBody()).isNotNull();
        assertThat(resp1.getBody().code()).isEqualTo("INVALID_RANGE");

        HttpEntity<BookingRequest> entity2 = new HttpEntity<>(
            new BookingRequest(journeyId, 3, 1),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp2 = rest.postForEntity("/api/bookings", entity2, ErrorResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().code()).isEqualTo("INVALID_RANGE");

        HttpEntity<BookingRequest> entity3 = new HttpEntity<>(
            new BookingRequest(journeyId, -1, 3),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp3 = rest.postForEntity("/api/bookings", entity3, ErrorResponse.class);
        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp3.getBody()).isNotNull();
        assertThat(resp3.getBody().code()).isEqualTo("INVALID_RANGE");

        HttpEntity<BookingRequest> entity4 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 6),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp4 = rest.postForEntity("/api/bookings", entity4, ErrorResponse.class);
        assertThat(resp4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp4.getBody()).isNotNull();
        assertThat(resp4.getBody().code()).isEqualTo("INVALID_RANGE");
    }

    @Test
    void success_returns201() {
        HttpEntity<BookingRequest> entity = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 3),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp = rest.postForEntity("/api/bookings", entity, BookingResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.bookingId()).isNotNull();
        assertThat(body.pnr()).isNotBlank();
        assertThat(body.status()).isEqualTo(BookingStatus.HELD);
        assertThat(body.seatId()).isEqualTo(seatIds.get(0));
        assertThat(body.coach()).isEqualTo("C1");
        assertThat(body.seatNo()).isEqualTo(1);
        assertThat(body.holdExpiresAt()).isNotNull();
        assertThat(body.price()).isNotNull();
    }

    @Test
    void seatChosenIsLowestFree() {
        HttpEntity<BookingRequest> entity = new HttpEntity<>(
            new BookingRequest(journeyId, 1, 4),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp = rest.postForEntity("/api/bookings", entity, BookingResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().seatId()).isEqualTo(seatIds.get(0));
    }

    @Test
    void twoNonOverlappingBookings_shareOneSeat() {
        // User 1 books stop 0 -> 2 (segments 0, 1)
        HttpEntity<BookingRequest> req1 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp1 = rest.postForEntity("/api/bookings", req1, BookingResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp1.getBody()).isNotNull();
        assertThat(resp1.getBody().seatId()).isEqualTo(seatIds.get(0));

        // User 2 books stop 2 -> 4 (segments 2, 3) -> no overlap, shares seat 1
        HttpEntity<BookingRequest> req2 = new HttpEntity<>(
            new BookingRequest(journeyId, 2, 4),
            authHeaders(user2Token)
        );
        ResponseEntity<BookingResponse> resp2 = rest.postForEntity("/api/bookings", req2, BookingResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().seatId()).isEqualTo(seatIds.get(0));
    }

    @Test
    void overlappingBookings_goToDifferentSeats() {
        // User 1 books stop 0 -> 2 (segments 0, 1) -> gets seat 1
        HttpEntity<BookingRequest> req1 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp1 = rest.postForEntity("/api/bookings", req1, BookingResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp1.getBody()).isNotNull();
        assertThat(resp1.getBody().seatId()).isEqualTo(seatIds.get(0));

        // User 2 books stop 1 -> 3 (segments 1, 2) -> overlaps on segment 1, gets seat 2
        HttpEntity<BookingRequest> req2 = new HttpEntity<>(
            new BookingRequest(journeyId, 1, 3),
            authHeaders(user2Token)
        );
        ResponseEntity<BookingResponse> resp2 = rest.postForEntity("/api/bookings", req2, BookingResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().seatId()).isEqualTo(seatIds.get(1));
    }

    @Test
    void soldOut_returns409() {
        // User 1 books stop 0 -> 2 -> seat 1
        HttpEntity<BookingRequest> req1 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp1 = rest.postForEntity("/api/bookings", req1, BookingResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // User 2 books stop 0 -> 2 -> seat 2
        HttpEntity<BookingRequest> req2 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user2Token)
        );
        ResponseEntity<BookingResponse> resp2 = rest.postForEntity("/api/bookings", req2, BookingResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Create User 3
        KeyHolder u3Kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO users (name, email, password_hash, role) VALUES ('User 3', 'u3@example.com', 'hash', 'USER')",
                Statement.RETURN_GENERATED_KEYS
            );
            return ps;
        }, u3Kh);
        String user3Token = jwtService.generateToken(extractId(u3Kh), "USER");

        // User 3 tries to book stop 0 -> 2 -> SOLD_OUT
        HttpEntity<BookingRequest> req3 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user3Token)
        );
        ResponseEntity<ErrorResponse> resp3 = rest.postForEntity("/api/bookings", req3, ErrorResponse.class);
        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp3.getBody()).isNotNull();
        assertThat(resp3.getBody().code()).isEqualTo("SOLD_OUT");
    }

    @Test
    void userWithOverlappingBooking_returns409() {
        // User 1 books stop 0 -> 2
        HttpEntity<BookingRequest> req1 = new HttpEntity<>(
            new BookingRequest(journeyId, 0, 2),
            authHeaders(user1Token)
        );
        ResponseEntity<BookingResponse> resp1 = rest.postForEntity("/api/bookings", req1, BookingResponse.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same User 1 tries to book stop 1 -> 3 on same journey -> 409 CONFLICTING_BOOKING
        HttpEntity<BookingRequest> req2 = new HttpEntity<>(
            new BookingRequest(journeyId, 1, 3),
            authHeaders(user1Token)
        );
        ResponseEntity<ErrorResponse> resp2 = rest.postForEntity("/api/bookings", req2, ErrorResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().code()).isEqualTo("CONFLICTING_BOOKING");
    }
}
