package com.seatrush;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("mysqltest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NaiveBookingConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private NaiveBooking naiveBooking;

    @BeforeAll
    void checkDatabaseAndInitExtraTable() {
        // Safety assertion: strictly require seatrush_test
        String currentDb = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(currentDb)
            .as("Target schema must be seatrush_test to ensure test safety")
            .isEqualTo("seatrush_test");

        // Extra table for unconstrained baseline race demonstration
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS naive_seat_allocations (" +
            "  journey_id BIGINT NOT NULL," +
            "  seat_id BIGINT NOT NULL," +
            "  segment_index INT NOT NULL," +
            "  booking_ref VARCHAR(64) NOT NULL" +
            ") ENGINE=InnoDB"
        );

        naiveBooking = new NaiveBooking(jdbcTemplate);
    }

    @AfterAll
    void tearDown() {
        // Ensure no lingering test data with 'cc-' prefix
        // Safe FK-ordered cleanup if any abandoned data exists
        jdbcTemplate.update("DELETE FROM naive_seat_allocations WHERE booking_ref LIKE 'cc-%'");
    }

    private static class Fixture {
        final List<Long> stationIds = new ArrayList<>();
        Long trainId;
        final List<Long> seatIds = new ArrayList<>();
        Long journeyId;
        final List<Long> userIds = new ArrayList<>();
    }

    private Fixture createFixture(String roundTag) {
        Fixture fixture = new Fixture();

        // 1. 6 stations (code max 10 chars)
        for (int i = 0; i < 6; i++) {
            final int stationIdx = i;
            String code = "cc-" + roundTag + stationIdx;
            KeyHolder kh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stations (code, name, city) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, code);
                ps.setString(2, "cc-Station-" + stationIdx);
                ps.setString(3, "cc-City-" + stationIdx);
                return ps;
            }, kh);
            fixture.stationIds.add(kh.getKey().longValue());
        }

        // 2. 1 train with 10 seats
        String trainNo = "cc-tr-" + roundTag;
        KeyHolder trainKh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO trains (number, name) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, trainNo);
            ps.setString(2, "cc-Express-" + roundTag);
            return ps;
        }, trainKh);
        fixture.trainId = trainKh.getKey().longValue();

        // 3. 6 train stops (segments 0..4)
        for (int i = 0; i < 6; i++) {
            jdbcTemplate.update(
                "INSERT INTO train_stops (train_id, stop_index, station_id, arrival_offset_min, departure_offset_min, distance_km) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                fixture.trainId, i, fixture.stationIds.get(i), i * 30, i * 30 + 5, i * 50
            );
        }

        // 4. 10 seats
        for (int i = 1; i <= 10; i++) {
            final int seatNo = i;
            KeyHolder seatKh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO seats (train_id, coach, seat_no, seat_type) VALUES (?, 'C1', ?, 'STANDARD')",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setLong(1, fixture.trainId);
                ps.setInt(2, seatNo);
                return ps;
            }, seatKh);
            fixture.seatIds.add(seatKh.getKey().longValue());
        }

        // 5. 1 journey
        KeyHolder journeyKh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO journeys (train_id, travel_date, booking_opens_at, status) " +
                "VALUES (?, CURDATE(), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'OPEN')",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, fixture.trainId);
            return ps;
        }, journeyKh);
        fixture.journeyId = journeyKh.getKey().longValue();

        // 6. 100 users
        for (int i = 1; i <= 100; i++) {
            final int userIdx = i;
            KeyHolder userKh = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, 'cc-hash', 'USER')",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, "cc-user-" + userIdx);
                ps.setString(2, "cc-" + roundTag + "-" + userIdx + "@test.com");
                return ps;
            }, userKh);
            fixture.userIds.add(userKh.getKey().longValue());
        }

        return fixture;
    }

    private void cleanupFixture(Fixture fixture) {
        if (fixture == null) return;
        if (fixture.journeyId != null) {
            jdbcTemplate.update("DELETE FROM seat_allocations WHERE journey_id = ?", fixture.journeyId);
            jdbcTemplate.update("DELETE FROM naive_seat_allocations WHERE journey_id = ?", fixture.journeyId);
            jdbcTemplate.update("DELETE FROM bookings WHERE journey_id = ?", fixture.journeyId);
            jdbcTemplate.update("DELETE FROM journeys WHERE id = ?", fixture.journeyId);
        }
        if (fixture.trainId != null) {
            jdbcTemplate.update("DELETE FROM seats WHERE train_id = ?", fixture.trainId);
            jdbcTemplate.update("DELETE FROM train_stops WHERE train_id = ?", fixture.trainId);
            jdbcTemplate.update("DELETE FROM trains WHERE id = ?", fixture.trainId);
        }
        for (Long stationId : fixture.stationIds) {
            jdbcTemplate.update("DELETE FROM stations WHERE id = ?", stationId);
        }
        for (Long userId : fixture.userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void test1_naiveBooking_unconstrainedTable_demonstratesDoubleAllocation() throws InterruptedException {
        boolean reproduced = false;
        final int userCount = 100;

        for (int round = 1; round <= 5; round++) {
            String roundTag = "r1" + round + (int) (System.currentTimeMillis() % 1000);
            Fixture fixture = createFixture(roundTag);
            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(userCount);

            AtomicInteger successes = new AtomicInteger();
            AtomicInteger soldOut = new AtomicInteger();
            List<Throwable> errors = new CopyOnWriteArrayList<>();

            try {
                for (int i = 0; i < userCount; i++) {
                    final long userId = fixture.userIds.get(i);
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            NaiveBooking.Result res = naiveBooking.book(
                                fixture.journeyId, userId, 0, 5, "naive_seat_allocations"
                            );
                            if (res == NaiveBooking.Result.SUCCESS) {
                                successes.incrementAndGet();
                            } else if (res == NaiveBooking.Result.SOLD_OUT) {
                                soldOut.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                // Fire all 100 threads concurrently
                startGate.countDown();
                boolean finished = doneGate.await(30, TimeUnit.SECONDS);
                assertThat(finished).as("Round " + round + " timed out waiting for threads").isTrue();

                if (!errors.isEmpty()) {
                    fail("Round " + round + " had unexpected exceptions: " + errors.get(0).getMessage());
                }

                // Count double-allocated seat-segments
                Integer doubleAllocCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT seat_id, segment_index FROM naive_seat_allocations " +
                    "  WHERE journey_id = ? " +
                    "  GROUP BY journey_id, seat_id, segment_index " +
                    "  HAVING COUNT(*) > 1" +
                    ") dup",
                    Integer.class,
                    fixture.journeyId
                );

                int dCount = doubleAllocCount != null ? doubleAllocCount : 0;
                int sCount = successes.get();
                int oCount = soldOut.get();

                System.out.printf(
                    "[Test 1 Round %d] Successes: %d, SOLD_OUT: %d, Double-allocated seat-segments: %d (expected correct: 10 successes, 0 double)%n",
                    round, sCount, oCount, dCount
                );

                if (dCount > 0 || sCount > 10) {
                    reproduced = true;
                    break;
                }
            } finally {
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                cleanupFixture(fixture);
            }
        }

        assertThat(reproduced)
            .as("Expected double allocation or more than 10 successes within 5 rounds on unconstrained table")
            .isTrue();
    }

    @Test
    void test2_naiveBooking_realSeatAllocations_zeroDoubleAllocationsDueToUniqueConstraint() throws InterruptedException {
        final int userCount = 100;
        String roundTag = "r2" + (int) (System.currentTimeMillis() % 10000);
        Fixture fixture = createFixture(roundTag);

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(userCount);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger duplicateKeyErrors = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < userCount; i++) {
                final long userId = fixture.userIds.get(i);
                executor.submit(() -> {
                    try {
                        startGate.await();
                        NaiveBooking.Result res = naiveBooking.book(
                            fixture.journeyId, userId, 0, 5, "seat_allocations"
                        );
                        if (res == NaiveBooking.Result.SUCCESS) {
                            successes.incrementAndGet();
                        } else if (res == NaiveBooking.Result.SOLD_OUT) {
                            soldOut.incrementAndGet();
                        }
                    } catch (DuplicateKeyException dke) {
                        duplicateKeyErrors.incrementAndGet();
                    } catch (DataIntegrityViolationException dive) {
                        if (dive.getMessage() != null && dive.getMessage().toLowerCase().contains("duplicate")) {
                            duplicateKeyErrors.incrementAndGet();
                        } else {
                            unexpectedErrors.add(dive);
                        }
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneGate.await(30, TimeUnit.SECONDS);
            assertThat(finished).as("Test 2 timed out waiting for threads").isTrue();

            assertThat(unexpectedErrors)
                .as("Unexpected exception types occurred during test 2: " + unexpectedErrors)
                .isEmpty();

            // Verify zero double-allocated seat-segments in real seat_allocations
            Integer doubleAllocCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                "  SELECT seat_id, segment_index FROM seat_allocations " +
                "  WHERE journey_id = ? " +
                "  GROUP BY journey_id, seat_id, segment_index " +
                "  HAVING COUNT(*) > 1" +
                ") dup",
                Integer.class,
                fixture.journeyId
            );

            int dCount = doubleAllocCount != null ? doubleAllocCount : 0;
            int sCount = successes.get();
            int oCount = soldOut.get();
            int dupKeyCount = duplicateKeyErrors.get();

            System.out.printf(
                "[Test 2 Real Constraint] Successes: %d, SOLD_OUT: %d, Duplicate-key errors prevented: %d, Double-allocated seat-segments: %d%n",
                sCount, oCount, dupKeyCount, dCount
            );

            assertThat(dCount)
                .as("seat_allocations unique constraint must guarantee zero double-allocated seat-segments")
                .isZero();
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            cleanupFixture(fixture);
        }
    }
}
