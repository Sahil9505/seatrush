package com.seatrush.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Profile("dev")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer studentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM students", Integer.class);

        if (studentCount != null && studentCount > 0) {
            log.info("Database already seeded ({} students found). Skipping seeding.", studentCount);
            return;
        }

        log.info("Seeding dev database...");
        long startTime = System.currentTimeMillis();

        // 1. Compute ONE BCrypt hash
        String passwordHash = passwordEncoder.encode("password123");

        // 2. Seed 1 admin
        jdbcTemplate.update(
                "INSERT INTO students (roll_no, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                "ADMIN001", "Admin", "admin@seatrush.test", passwordHash, "ADMIN"
        );

        // 3. Seed 5,000 students in batches of 500
        List<Object[]> studentRows = new ArrayList<>(5000);
        for (int i = 1; i <= 5000; i++) {
            studentRows.add(new Object[]{
                    String.format("R%04d", i),
                    String.format("Student %04d", i),
                    String.format("student%04d@seatrush.test", i),
                    passwordHash,
                    "STUDENT"
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO students (roll_no, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                studentRows,
                500,
                (ps, row) -> {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                    ps.setString(5, (String) row[4]);
                }
        );

        // 4. Seed 20 courses
        List<Object[]> courseRows = new ArrayList<>(20);
        for (int i = 1; i <= 20; i++) {
            courseRows.add(new Object[]{
                    String.format("CS%03d", i),
                    String.format("Course %02d", i),
                    3
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO courses (code, title, credits) VALUES (?, ?, ?)",
                courseRows,
                20,
                (ps, row) -> {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setInt(3, (Integer) row[2]);
                }
        );

        List<Long> courseIds = jdbcTemplate.queryForList(
                "SELECT id FROM courses ORDER BY id ASC", Long.class);

        // 5. Seed 50 sections spread across the 20 courses
        // capacity random between 30 and 60, seats_taken = 0, opens_at in the past
        Timestamp opensAt = Timestamp.valueOf(LocalDateTime.now().minusDays(1));
        List<Object[]> sectionRows = new ArrayList<>(50);
        for (int i = 0; i < 50; i++) {
            Long courseId = courseIds.get(i % courseIds.size());
            String sectionCode = String.format("S%d", (i / courseIds.size()) + 1);
            int capacity = ThreadLocalRandom.current().nextInt(30, 61);
            String instructor = String.format("Prof. %s", (char) ('A' + (i % 26)));
            String schedule = "MWF 10:00-11:00";

            sectionRows.add(new Object[]{
                    courseId,
                    sectionCode,
                    instructor,
                    schedule,
                    capacity,
                    0,
                    opensAt
            });
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO sections (course_id, section_code, instructor, schedule, capacity, seats_taken, opens_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sectionRows,
                50,
                (ps, row) -> {
                    ps.setLong(1, (Long) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                    ps.setInt(5, (Integer) row[4]);
                    ps.setInt(6, (Integer) row[5]);
                    ps.setTimestamp(7, (Timestamp) row[6]);
                }
        );

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Dev seed completed in {} ms (1 admin, 5000 students, 20 courses, 50 sections).", elapsed);
    }
}
