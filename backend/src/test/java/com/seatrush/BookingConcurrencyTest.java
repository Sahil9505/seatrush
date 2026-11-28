package com.seatrush;

import com.seatrush.dto.BookingRequest;
import com.seatrush.dto.BookingResponse;
import com.seatrush.entity.BookingStatus;
import com.seatrush.exception.AppException;
import com.seatrush.exception.LockTimeoutException;
import com.seatrush.exception.SoldOutException;
import com.seatrush.service.BookingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mysqltest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookingService bookingService;

    @BeforeAll
    void checkDatabase() {
        String currentDb = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(currentDb)
            .as("Target schema must be seatrush_test to ensure test safety")
            .isEqualTo("seatrush_test");
    }

    @AfterAll
    void tearDown() {
        // Ensure no lingering test data with 'cc-' prefix
        jdbcTemplate.update("DELETE FROM seat_allocations WHERE journey_id IN (SELECT id FROM journeys WHERE train_id IN (SELECT id FROM trains WHERE number LIKE 'cc-%'))");
        jdbcTemplate.update("DELETE FROM bookings WHERE journey_id IN (SELECT id FROM journeys WHERE train_id IN (SELECT id FROM trains WHERE number LIKE 'cc-%'))");
        jdbcTemplate.update("DELETE FROM journeys WHERE train_id IN (SELECT id FROM trains WHERE number LIKE 'cc-%')");
        jdbcTemplate.update("DELETE FROM seats WHERE train_id IN (SELECT id FROM trains WHERE number LIKE 'cc-%')");
        jdbcTemplate.update("DELETE FROM train_stops WHERE train_id IN (SELECT id FROM trains WHERE number LIKE 'cc-%')");
        jdbcTemplate.update("DELETE FROM trains WHERE number LIKE 'cc-%'");
        jdbcTemplate.update("DELETE FROM stations WHERE code LIKE 'cc-%'");
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'cc-%'");
    }

    private static class Fixture {
        final List<Long> stationIds = new ArrayList<>();
        Long trainId;
        final List<Long> seatIds = new ArrayList<>();
        Long journeyId;
        final List<Long> userIds = new ArrayList<>();
    }

    private Fixture createFixture(String roundTag, int userCount) {
        Fixture fixture = new Fixture();

        // 1. 6 stations (code max 10 chars, prefix 'cc-')
        for (int i = 0; i < 6; i++) {
            final int stationIdx = i;
            String code = "cc-" + roundTag.substring(0, Math.min(roundTag.length(), 4)) + stationIdx;
            KeyHolder kh = new GeneratedKeyHolder();
            String finalCode = code;
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stations (code, name, city) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, finalCode);
                ps.setString(2, "cc-Station-" + stationIdx);
                ps.setString(3, "cc-City-" + stationIdx);
                return ps;
            }, kh);
            fixture.stationIds.add(kh.getKey().longValue());
        }

        // 2. 1 train
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

        // 6. Users
        for (int i = 1; i <= userCount; i++) {
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
    void test1_fullRoute_100threads_exactly10Held_90SoldOut_50Allocations() throws InterruptedException {
        final int userCount = 100;

        for (int round = 1; round <= 5; round++) {
            String roundTag = "t1r" + round + (int) (System.currentTimeMillis() % 1000);
            Fixture fixture = createFixture(roundTag, userCount);

            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(userCount);

            AtomicInteger heldCount = new AtomicInteger(0);
            AtomicInteger soldOutCount = new AtomicInteger(0);
            List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

            try {
                for (int i = 0; i < userCount; i++) {
                    final long userId = fixture.userIds.get(i);
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            BookingResponse resp = bookingService.book(
                                userId,
                                new BookingRequest(fixture.journeyId, 0, 5)
                            );
                            if (resp.status() == BookingStatus.HELD) {
                                heldCount.incrementAndGet();
                            } else {
                                unexpectedExceptions.add(new IllegalStateException("Unexpected status: " + resp.status()));
                            }
                        } catch (SoldOutException e) {
                            soldOutCount.incrementAndGet();
                        } catch (LockTimeoutException e) {
                            // Valid concurrency backpressure outcome after 3 retries under extreme contention
                        } catch (AppException e) {
                            if ("SOLD_OUT".equals(e.getCode()) || "LOCK_TIMEOUT".equals(e.getCode())) {
                                if ("SOLD_OUT".equals(e.getCode())) {
                                    soldOutCount.incrementAndGet();
                                }
                            } else {
                                unexpectedExceptions.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedExceptions.add(t);
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                startGate.countDown();
                boolean finished = doneGate.await(60, TimeUnit.SECONDS);
                assertThat(finished).as("Round " + round + " tasks must finish within timeout").isTrue();

                assertThat(unexpectedExceptions)
                    .as("Round " + round + " should have no unexpected exceptions")
                    .isEmpty();

                assertThat(heldCount.get())
                    .as("Round " + round + " exactly 10 HELD bookings expected")
                    .isEqualTo(10);

                assertThat(soldOutCount.get())
                    .as("Round " + round + " exactly 90 SOLD_OUT expected")
                    .isEqualTo(90);

                Integer totalAllocations = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM seat_allocations WHERE journey_id = ?",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(totalAllocations)
                    .as("Round " + round + " total seat_allocations must be 50 (10 bookings * 5 segments)")
                    .isEqualTo(50);

                Integer duplicateSegments = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT seat_id, segment_index FROM seat_allocations WHERE journey_id = ? " +
                    "  GROUP BY seat_id, segment_index HAVING COUNT(*) > 1" +
                    ") t",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(duplicateSegments)
                    .as("Round " + round + " zero double-allocated seat-segments")
                    .isEqualTo(0);

            } finally {
                executor.shutdown();
                cleanupFixture(fixture);
            }
        }
    }

    @Test
    void test2_randomRanges_200threads_zeroDoubleAllocations_exactSegments() throws InterruptedException {
        final int userCount = 200;

        for (int round = 1; round <= 5; round++) {
            final int currentRound = round;
            String roundTag = "t2r" + round + (int) (System.currentTimeMillis() % 1000);
            Fixture fixture = createFixture(roundTag, userCount);

            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(userCount);

            AtomicInteger heldCount = new AtomicInteger(0);
            AtomicInteger soldOutCount = new AtomicInteger(0);
            List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

            try {
                for (int i = 0; i < userCount; i++) {
                    final long userId = fixture.userIds.get(i);
                    final int threadIndex = i;
                    executor.submit(() -> {
                        try {
                            Random random = new Random(currentRound * 1000L + threadIndex);
                            int from = random.nextInt(5); // 0..4
                            int to = from + 1 + random.nextInt(6 - from - 1); // from+1..5

                            startGate.await();
                            BookingResponse resp = bookingService.book(
                                userId,
                                new BookingRequest(fixture.journeyId, from, to)
                            );
                            if (resp.status() == BookingStatus.HELD) {
                                heldCount.incrementAndGet();
                            } else {
                                unexpectedExceptions.add(new IllegalStateException("Unexpected status: " + resp.status()));
                            }
                        } catch (SoldOutException e) {
                            soldOutCount.incrementAndGet();
                        } catch (LockTimeoutException e) {
                            // Valid concurrency backpressure outcome after 3 retries under extreme contention
                        } catch (AppException e) {
                            if ("SOLD_OUT".equals(e.getCode()) || "LOCK_TIMEOUT".equals(e.getCode())) {
                                if ("SOLD_OUT".equals(e.getCode())) {
                                    soldOutCount.incrementAndGet();
                                }
                            } else {
                                unexpectedExceptions.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedExceptions.add(t);
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                startGate.countDown();
                boolean finished = doneGate.await(60, TimeUnit.SECONDS);
                assertThat(finished).as("Round " + round + " tasks must finish within timeout").isTrue();

                assertThat(unexpectedExceptions)
                    .as("Round " + round + " should have no unexpected exceptions")
                    .isEmpty();

                Integer duplicateSegments = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT seat_id, segment_index FROM seat_allocations WHERE journey_id = ? " +
                    "  GROUP BY seat_id, segment_index HAVING COUNT(*) > 1" +
                    ") t",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(duplicateSegments)
                    .as("Round " + round + " zero double-allocated seat-segments")
                    .isEqualTo(0);

                Integer mismatchedBookingSegments = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT b.id, (b.to_stop_index - b.from_stop_index) AS expected_segments, " +
                    "         COUNT(sa.segment_index) AS actual_segments " +
                    "  FROM bookings b " +
                    "  LEFT JOIN seat_allocations sa ON b.id = sa.booking_id " +
                    "  WHERE b.journey_id = ? AND b.status = 'HELD' " +
                    "  GROUP BY b.id, b.to_stop_index, b.from_stop_index " +
                    "  HAVING expected_segments != actual_segments" +
                    ") t",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(mismatchedBookingSegments)
                    .as("Round " + round + " every HELD booking must have exactly (to - from) allocation rows")
                    .isEqualTo(0);

            } finally {
                executor.shutdown();
                cleanupFixture(fixture);
            }
        }
    }

    @Test
    void test3_singleSegment_100threads_exactly10Held() throws InterruptedException {
        final int userCount = 100;

        for (int round = 1; round <= 5; round++) {
            String roundTag = "t3r" + round + (int) (System.currentTimeMillis() % 1000);
            Fixture fixture = createFixture(roundTag, userCount);

            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(userCount);

            AtomicInteger heldCount = new AtomicInteger(0);
            AtomicInteger soldOutCount = new AtomicInteger(0);
            List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

            try {
                for (int i = 0; i < userCount; i++) {
                    final long userId = fixture.userIds.get(i);
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            BookingResponse resp = bookingService.book(
                                userId,
                                new BookingRequest(fixture.journeyId, 0, 1)
                            );
                            if (resp.status() == BookingStatus.HELD) {
                                heldCount.incrementAndGet();
                            } else {
                                unexpectedExceptions.add(new IllegalStateException("Unexpected status: " + resp.status()));
                            }
                        } catch (SoldOutException e) {
                            soldOutCount.incrementAndGet();
                        } catch (LockTimeoutException e) {
                            // Valid concurrency backpressure outcome after 3 retries under extreme contention
                        } catch (AppException e) {
                            if ("SOLD_OUT".equals(e.getCode()) || "LOCK_TIMEOUT".equals(e.getCode())) {
                                if ("SOLD_OUT".equals(e.getCode())) {
                                    soldOutCount.incrementAndGet();
                                }
                            } else {
                                unexpectedExceptions.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedExceptions.add(t);
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                startGate.countDown();
                boolean finished = doneGate.await(60, TimeUnit.SECONDS);
                assertThat(finished).as("Round " + round + " tasks must finish within timeout").isTrue();

                assertThat(unexpectedExceptions)
                    .as("Round " + round + " should have no unexpected exceptions")
                    .isEmpty();

                assertThat(heldCount.get())
                    .as("Round " + round + " exactly 10 HELD bookings expected")
                    .isEqualTo(10);

                assertThat(soldOutCount.get())
                    .as("Round " + round + " exactly 90 SOLD_OUT expected")
                    .isEqualTo(90);

                Integer totalAllocations = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM seat_allocations WHERE journey_id = ?",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(totalAllocations)
                    .as("Round " + round + " total seat_allocations must be 10 (10 bookings * 1 segment)")
                    .isEqualTo(10);

                Integer duplicateSegments = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT seat_id, segment_index FROM seat_allocations WHERE journey_id = ? " +
                    "  GROUP BY seat_id, segment_index HAVING COUNT(*) > 1" +
                    ") t",
                    Integer.class,
                    fixture.journeyId
                );
                assertThat(duplicateSegments)
                    .as("Round " + round + " zero double-allocated seat-segments")
                    .isEqualTo(0);

            } finally {
                executor.shutdown();
                cleanupFixture(fixture);
            }
        }
    }
}
