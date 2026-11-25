package com.seatrush;

import com.seatrush.exception.AppException;
import com.seatrush.service.BookingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
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
class BookingConcurrencyTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<Long> studentIds;
    private Long courseId;

    @BeforeEach
    void setUp() {
        cleanup();
        studentIds = createStudents(200);
        courseId = createCourse();
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

    private List<Long> createStudents(int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            batch.add(new Object[]{
                    "cc-r-" + i,
                    "Conc Student " + i,
                    "cc-s-" + i + "@test.com",
                    "$2a$10$abcdefghijklmnopqrstuvwxyz123456",
                    "STUDENT"
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO students (roll_no, name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                batch
        );
        return jdbcTemplate.queryForList(
                "SELECT id FROM students WHERE email LIKE 'cc-%' ORDER BY id ASC",
                Long.class
        );
    }

    private Long createCourse() {
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "cc-" + randomSuffix;
        jdbcTemplate.update("INSERT INTO courses (code, title, credits) VALUES (?, ?, ?)",
                code, "Concurrency Test Course", 3);
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

    @RepeatedTest(5)
    @DisplayName("Test 1: 200 threads competing for 10 seats -> exactly 10 confirmed, 190 SECTION_FULL")
    void test200StudentsCompeteFor10Seats() throws InterruptedException {
        Long sectionId = createSection(courseId, "SEC-T1", 10);
        int numThreads = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyGate = new CountDownLatch(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger sectionFulls = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        for (Long studentId : studentIds) {
            executor.submit(() -> {
                readyGate.countDown();
                try {
                    startGate.await();
                    bookingService.register(studentId, sectionId);
                    successes.incrementAndGet();
                } catch (AppException e) {
                    if ("SECTION_FULL".equals(e.getCode())) {
                        sectionFulls.incrementAndGet();
                    } else {
                        unexpectedExceptions.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                }
            });
        }

        assertThat(readyGate.await(10, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedExceptions).isEmpty();
        assertThat(successes.get()).isEqualTo(10);
        assertThat(sectionFulls.get()).isEqualTo(190);

        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(10);

        Integer confirmedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE section_id = ? AND status = 'CONFIRMED'",
                Integer.class, sectionId);
        assertThat(confirmedCount).isEqualTo(10);
    }

    @RepeatedTest(5)
    @DisplayName("Test 2: 1 student fires 50 simultaneous requests -> exactly 1 confirmed, 49 ALREADY_REGISTERED")
    void testSingleStudentConcurrentRequests() throws InterruptedException {
        Long sectionId = createSection(courseId, "SEC-T2", 10);
        Long singleStudentId = studentIds.get(0);
        int numRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numRequests);
        CountDownLatch readyGate = new CountDownLatch(numRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger alreadyRegistered = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numRequests; i++) {
            executor.submit(() -> {
                readyGate.countDown();
                try {
                    startGate.await();
                    bookingService.register(singleStudentId, sectionId);
                    successes.incrementAndGet();
                } catch (AppException e) {
                    if ("ALREADY_REGISTERED".equals(e.getCode())) {
                        alreadyRegistered.incrementAndGet();
                    } else {
                        unexpectedExceptions.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                }
            });
        }

        assertThat(readyGate.await(10, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedExceptions).isEmpty();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(alreadyRegistered.get()).isEqualTo(49);

        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, sectionId);
        assertThat(seatsTaken).isEqualTo(1);

        Integer regCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM registrations WHERE section_id = ? AND student_id = ?",
                Integer.class, sectionId, singleStudentId);
        assertThat(regCount).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("Test 3: 200 students randomly spread across 5 sections of capacity 10 -> exactly 50 total confirmed")
    void test200StudentsAcross5Sections() throws InterruptedException {
        List<Long> sectionIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sectionIds.add(createSection(courseId, "SEC-T3-" + i, 10));
        }

        int[] counts = new int[5];
        List<Long> targetSections = new ArrayList<>(200);
        do {
            Arrays.fill(counts, 0);
            targetSections.clear();
            for (int i = 0; i < 200; i++) {
                int secIdx = ThreadLocalRandom.current().nextInt(5);
                counts[secIdx]++;
                targetSections.add(sectionIds.get(secIdx));
            }
        } while (Arrays.stream(counts).anyMatch(c -> c < 10));

        int numThreads = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyGate = new CountDownLatch(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger sectionFulls = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numThreads; i++) {
            final Long studentId = studentIds.get(i);
            final Long targetSectionId = targetSections.get(i);
            executor.submit(() -> {
                readyGate.countDown();
                try {
                    startGate.await();
                    bookingService.register(studentId, targetSectionId);
                    successes.incrementAndGet();
                } catch (AppException e) {
                    if ("SECTION_FULL".equals(e.getCode())) {
                        sectionFulls.incrementAndGet();
                    } else {
                        unexpectedExceptions.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                }
            });
        }

        assertThat(readyGate.await(10, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedExceptions).isEmpty();

        int totalConfirmed = 0;
        for (Long secId : sectionIds) {
            Integer seatsTaken = jdbcTemplate.queryForObject(
                    "SELECT seats_taken FROM sections WHERE id = ?", Integer.class, secId);
            Integer confirmedRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM registrations WHERE section_id = ? AND status = 'CONFIRMED'",
                    Integer.class, secId);

            assertThat(seatsTaken).isNotNull();
            assertThat(seatsTaken).isLessThanOrEqualTo(10);
            assertThat(seatsTaken).isEqualTo(confirmedRows);
            totalConfirmed += confirmedRows;
        }

        assertThat(totalConfirmed).isEqualTo(50);
    }
}
