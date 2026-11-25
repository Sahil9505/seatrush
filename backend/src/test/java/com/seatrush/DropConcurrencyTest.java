package com.seatrush;

import com.seatrush.exception.AppException;
import com.seatrush.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mysqltest")
class DropConcurrencyTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM registrations WHERE student_id IN (SELECT id FROM students WHERE email LIKE 'cc-%' OR email LIKE 'conc-%') " +
                "OR section_id IN (SELECT s.id FROM sections s JOIN courses c ON s.course_id = c.id WHERE c.code LIKE 'cc-%' OR c.code LIKE 'conc-%')");
        jdbcTemplate.update("DELETE FROM sections WHERE course_id IN (SELECT id FROM courses WHERE code LIKE 'cc-%' OR code LIKE 'conc-%')");
        jdbcTemplate.update("DELETE FROM courses WHERE code LIKE 'cc-%' OR code LIKE 'conc-%'");
        jdbcTemplate.update("DELETE FROM students WHERE email LIKE 'cc-%' OR roll_no LIKE 'cc-%' OR email LIKE 'conc-%' OR roll_no LIKE 'conc-%'");
    }

    private List<Long> createStudents(int count, String prefix) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            batch.add(new Object[]{
                    "cc-" + prefix + "-r-" + i,
                    "Drop Conc Student " + i,
                    "cc-" + prefix + "-s-" + i + "@test.com",
                    "$2a$10$abcdefghijklmnopqrstuvwxyz123456",
                    "STUDENT"
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO students (roll_no, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                batch
        );
        return jdbcTemplate.queryForList(
                "SELECT id FROM students WHERE email LIKE 'cc-" + prefix + "-%' ORDER BY id ASC",
                Long.class
        );
    }

    private Long createCourse(String suffix) {
        String code = "cc-" + suffix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        jdbcTemplate.update("INSERT INTO courses (code, title, credits) VALUES (?, ?, ?)",
                code, "Drop Concurrency Course", 3);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM courses WHERE code = ?",
                Long.class,
                code
        );
    }

    private Long createSection(Long courseId, String sectionCode, int capacity) {
        jdbcTemplate.update(
                "INSERT INTO sections (course_id, section_code, capacity, seats_taken, opens_at) VALUES (?, ?, ?, 0, NOW())",
                courseId, sectionCode, capacity
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sections WHERE course_id = ? AND section_code = ?",
                Long.class,
                courseId, sectionCode
        );
    }

    @RepeatedTest(3)
    @DisplayName("50 threads, same student dropping same section -> 1 success, 49 NOT_FOUND, seats_taken decremented once")
    void testFiftyThreadsSameStudentDropConcurrently() throws InterruptedException {
        List<Long> students = createStudents(1, "single");
        Long studentId = students.get(0);
        Long courseId = createCourse("c-single");
        Long sectionId = createSection(courseId, "SEC-S1", 10);

        // Register student first
        bookingService.register(studentId, sectionId);

        Integer seatsBefore = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsBefore).isEqualTo(1);

        int numThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyGate = new CountDownLatch(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger notFounds = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                readyGate.countDown();
                try {
                    startGate.await();
                    bookingService.drop(studentId, sectionId);
                    successes.incrementAndGet();
                } catch (AppException e) {
                    if ("NOT_FOUND".equals(e.getCode())) {
                        notFounds.incrementAndGet();
                    } else {
                        unexpectedExceptions.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                }
            });
        }

        readyGate.await();
        startGate.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedExceptions).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(notFounds.get()).isEqualTo(49);

        Integer seatsAfter = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsAfter).isEqualTo(0);

        String regStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM registrations WHERE student_id = ? AND section_id = ?",
                String.class, studentId, sectionId);
        assertThat(regStatus).isEqualTo("DROPPED");
    }

    @Test
    @DisplayName("100 students register and drop 10-seat section concurrently in a loop -> no deadlocks, seats_taken equals CONFIRMED rows")
    void test100StudentsRegisterAndDropConcurrentlyLoop() throws InterruptedException {
        int studentCount = 100;
        List<Long> studentIds = createStudents(studentCount, "loop");
        Long courseId = createCourse("c-loop");
        Long sectionId = createSection(courseId, "SEC-LOOP10", 10);

        int numThreads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyGate = new CountDownLatch(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        for (Long studentId : studentIds) {
            executor.submit(() -> {
                readyGate.countDown();
                try {
                    startGate.await();
                    for (int iter = 0; iter < 5; iter++) {
                        // Register attempt
                        try {
                            bookingService.register(studentId, sectionId);
                        } catch (AppException e) {
                            if (!"SECTION_FULL".equals(e.getCode()) && !"ALREADY_REGISTERED".equals(e.getCode())) {
                                unexpectedExceptions.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedExceptions.add(t);
                        }

                        // Jitter
                        try {
                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 4));
                        } catch (InterruptedException ignored) {
                        }

                        // Drop attempt with 50% probability
                        if (ThreadLocalRandom.current().nextBoolean()) {
                            try {
                                bookingService.drop(studentId, sectionId);
                            } catch (AppException e) {
                                if (!"NOT_FOUND".equals(e.getCode())) {
                                    unexpectedExceptions.add(e);
                                }
                            } catch (Throwable t) {
                                unexpectedExceptions.add(t);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    unexpectedExceptions.add(e);
                }
            });
        }

        readyGate.await();
        startGate.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedExceptions).isEmpty();

        Integer finalSeatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        Integer confirmedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE section_id = ? AND status = 'CONFIRMED'",
                Integer.class, sectionId);

        assertThat(finalSeatsTaken).isNotNull();
        assertThat(confirmedCount).isNotNull();
        assertThat(finalSeatsTaken).isEqualTo(confirmedCount);
        assertThat(finalSeatsTaken).isBetween(0, 10);
    }
}
