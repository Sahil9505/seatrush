package com.seatrush;

import com.seatrush.config.DataSeeder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSeeder dataSeeder;

    @Test
    @Order(1)
    void verifyInitialSeedCounts() {
        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM students WHERE role = 'ADMIN'", Integer.class);
        Integer studentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM students WHERE role = 'STUDENT'", Integer.class);
        Integer courseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM courses", Integer.class);
        Integer sectionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sections", Integer.class);
        Integer registrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations", Integer.class);

        assertThat(adminCount).isEqualTo(1);
        assertThat(studentCount).isEqualTo(5000);
        assertThat(courseCount).isEqualTo(20);
        assertThat(sectionCount).isEqualTo(50);
        assertThat(registrationCount).isEqualTo(0);
    }

    @Test
    @Order(2)
    void secondRunAddsNothing() {
        dataSeeder.run();

        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM students WHERE role = 'ADMIN'", Integer.class);
        Integer studentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM students WHERE role = 'STUDENT'", Integer.class);
        Integer courseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM courses", Integer.class);
        Integer sectionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sections", Integer.class);
        Integer registrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations", Integer.class);

        assertThat(adminCount).isEqualTo(1);
        assertThat(studentCount).isEqualTo(5000);
        assertThat(courseCount).isEqualTo(20);
        assertThat(sectionCount).isEqualTo(50);
        assertThat(registrationCount).isEqualTo(0);
    }
}
