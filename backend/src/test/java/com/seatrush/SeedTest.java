package com.seatrush;

import com.seatrush.config.DataSeeder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mysqltest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public DataSeeder testDataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
            return new DataSeeder(jdbcTemplate, passwordEncoder);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSeeder dataSeeder;

    @BeforeAll
    void cleanUpBefore() {
        cleanDatabase();
    }

    @AfterAll
    void cleanUpAfter() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM seat_allocations");
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("DELETE FROM booking_events");
        jdbcTemplate.update("DELETE FROM bookings");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM journeys");
        jdbcTemplate.update("DELETE FROM seats");
        jdbcTemplate.update("DELETE FROM train_stops");
        jdbcTemplate.update("DELETE FROM trains");
        jdbcTemplate.update("DELETE FROM stations");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    @Order(1)
    void verifyInitialSeedCounts() {
        dataSeeder.seedDevData();

        verifyCounts();
    }

    @Test
    @Order(2)
    void secondRunAddsNothing() {
        dataSeeder.seedDevData();

        verifyCounts();
    }

    private void verifyCounts() {
        Integer stationCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stations", Integer.class);
        Integer trainCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trains", Integer.class);
        Integer trainStopCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM train_stops", Integer.class);
        Integer seatCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seats", Integer.class);
        Integer journeyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journeys", Integer.class);
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        Integer bookingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class);

        assertThat(stationCount).isEqualTo(6);
        assertThat(trainCount).isEqualTo(2);
        assertThat(trainStopCount).isEqualTo(12);
        assertThat(seatCount).isEqualTo(144);
        assertThat(journeyCount).isEqualTo(14);
        assertThat(userCount).isEqualTo(5001);
        assertThat(bookingCount).isEqualTo(0);
    }
}
