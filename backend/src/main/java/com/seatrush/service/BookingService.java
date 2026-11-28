package com.seatrush.service;

import com.seatrush.availability.SegmentMask;
import com.seatrush.dto.BookingRequest;
import com.seatrush.dto.BookingResponse;
import com.seatrush.entity.Journey;
import com.seatrush.entity.JourneyStatus;
import com.seatrush.exception.ConflictingBookingException;
import com.seatrush.exception.InvalidRouteException;
import com.seatrush.exception.JourneyNotFoundException;
import com.seatrush.exception.JourneyNotOpenException;
import com.seatrush.exception.LockTimeoutException;
import com.seatrush.exception.SoldOutException;
import com.seatrush.repository.JourneyRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BookingService {

    private final JourneyRepository journeyRepository;
    private final BookingAttemptService bookingAttemptService;
    private final JdbcTemplate jdbcTemplate;

    public BookingService(
            JourneyRepository journeyRepository,
            BookingAttemptService bookingAttemptService,
            JdbcTemplate jdbcTemplate
    ) {
        this.journeyRepository = journeyRepository;
        this.bookingAttemptService = bookingAttemptService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private record SeatInfo(long id, String coach, int seatNo) {}

    /**
     * Books a seat on a journey for the requested stop range.
     * The attempt loop itself is NOT transactional.
     */
    public BookingResponse book(Long userId, BookingRequest request) {
        // 1. Validate: journey exists (404)
        Journey journey = journeyRepository.findById(request.journeyId())
            .orElseThrow(() -> new JourneyNotFoundException("Journey not found: " + request.journeyId()));

        // Validate: journey status is OPEN (409)
        if (journey.getStatus() != JourneyStatus.OPEN) {
            throw new JourneyNotOpenException("Journey is not open for booking: " + journey.getStatus());
        }

        // Validate: stopCount comes from train's train_stops
        Integer stopCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM train_stops WHERE train_id = ?",
            Integer.class,
            journey.getTrainId()
        );
        if (stopCount == null || stopCount < 2) {
            throw new InvalidRouteException("Train has invalid stop configuration: " + stopCount);
        }

        // Validate: 0 <= from < to <= stopCount-1
        int from = request.fromStopIndex();
        int to = request.toStopIndex();
        if (from < 0 || to <= from || to > stopCount - 1) {
            throw new InvalidRouteException("Invalid stop range [" + from + ", " + to + "] for stopCount " + stopCount);
        }

        // Validate: user has no overlapping HELD/CONFIRMED booking on this journey (409)
        Integer activeOverlaps = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bookings " +
            "WHERE user_id = ? AND journey_id = ? " +
            "  AND (status = 'CONFIRMED' OR (status = 'HELD' AND (hold_expires_at IS NULL OR hold_expires_at > NOW()))) " +
            "  AND from_stop_index < ? AND to_stop_index > ?",
            Integer.class,
            userId, journey.getId(), to, from
        );
        if (activeOverlaps != null && activeOverlaps > 0) {
            throw new ConflictingBookingException("User already has an overlapping active booking on this journey");
        }

        // 2. reqMask = SegmentMask.requestMask(from, to, stopCount)
        long reqMask = SegmentMask.requestMask(from, to, stopCount);

        List<SeatInfo> seats = jdbcTemplate.query(
            "SELECT s.id, s.coach, s.seat_no FROM seats s WHERE s.train_id = ? ORDER BY s.id",
            (rs, rowNum) -> new SeatInfo(rs.getLong("id"), rs.getString("coach"), rs.getInt("seat_no")),
            journey.getTrainId()
        );
        if (seats.isEmpty()) {
            throw new SoldOutException("No seats available for the journey");
        }

        BigDecimal price = BigDecimal.valueOf((to - from) * 50.0).setScale(2, RoundingMode.HALF_UP);
        Set<Long> triedSeats = new HashSet<>();
        int lockTimeoutRetries = 0;

        // 3. Attempt loop (the loop itself NOT transactional), with a per-request set triedSeats:
        // The UNIQUE(journey_id, seat_id, segment_index) constraint is the real guard and the read is only a hint.
        while (true) {
            // a. Read live occupancy and build one mask per seat; treat every seat in triedSeats as fully occupied.
            long[] seatMasks = new long[seats.size()];
            Map<Long, Integer> seatIndexMap = new HashMap<>(seats.size());
            for (int i = 0; i < seats.size(); i++) {
                SeatInfo seat = seats.get(i);
                seatIndexMap.put(seat.id(), i);
                if (triedSeats.contains(seat.id())) {
                    seatMasks[i] = ~0L; // Treat every seat in triedSeats as fully occupied
                }
            }

            jdbcTemplate.query(
                "SELECT seat_id, segment_index FROM seat_allocations WHERE journey_id = ?",
                rs -> {
                    long seatId = rs.getLong("seat_id");
                    int segmentIndex = rs.getInt("segment_index");
                    Integer idx = seatIndexMap.get(seatId);
                    if (idx != null && segmentIndex >= 0 && segmentIndex < 63) {
                        seatMasks[idx] |= (1L << segmentIndex);
                    }
                },
                journey.getId()
            );

            // findFirstFreeSeat. -1 -> 409 SOLD_OUT.
            int chosenIndex = SegmentMask.findFirstFreeSeat(seatMasks, reqMask);
            if (chosenIndex == -1) {
                throw new SoldOutException("No available seats for the requested segment");
            }

            SeatInfo chosenSeat = seats.get(chosenIndex);

            // b. In ONE new transaction: insert booking and seat_allocations rows in ASCENDING segment_index order.
            try {
                return bookingAttemptService.tryAllocate(
                    journey.getId(),
                    userId,
                    from,
                    to,
                    chosenSeat.id(),
                    chosenSeat.coach(),
                    chosenSeat.seatNo(),
                    price
                );
            } catch (DuplicateKeyException e) {
                // c. On a duplicate-key error: that seat has a committed conflicting row, so add it to triedSeats, roll back, go to (a).
                // Every duplicate-key failure removes one seat from consideration, so a request needs at most
                // seatCount attempts and SOLD_OUT is exact.
                triedSeats.add(chosenSeat.id());
            } catch (DataIntegrityViolationException e) {
                if (isDuplicateKeyViolation(e)) {
                    // Every duplicate-key failure removes one seat from consideration, so a request needs at most
                    // seatCount attempts and SOLD_OUT is exact.
                    triedSeats.add(chosenSeat.id());
                } else {
                    throw e;
                }
            } catch (ConcurrencyFailureException e) {
                // On a deadlock or lock-timeout: roll back and retry from (a) without adding to triedSeats,
                // at most 3 such retries per request, then fail with 503 and a clear error code (never SOLD_OUT).
                lockTimeoutRetries++;
                if (lockTimeoutRetries > 3) {
                    throw new LockTimeoutException("Seat allocation failed due to lock contention after 3 retries");
                }
                try {
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(5, 25));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                if (isLockContentionException(e)) {
                    lockTimeoutRetries++;
                    if (lockTimeoutRetries > 3) {
                        throw new LockTimeoutException("Seat allocation failed due to lock contention after 3 retries");
                    }
                    try {
                        Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(5, 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    private boolean isDuplicateKeyViolation(Throwable t) {
        if (t instanceof DuplicateKeyException) {
            return true;
        }
        while (t != null) {
            if (t instanceof SQLException sqlEx) {
                int err = sqlEx.getErrorCode();
                String state = sqlEx.getSQLState();
                if (err == 1062 || "23505".equals(state)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private boolean isLockContentionException(Throwable t) {
        if (t instanceof CannotAcquireLockException
                || t instanceof DeadlockLoserDataAccessException
                || t instanceof PessimisticLockingFailureException
                || t instanceof ConcurrencyFailureException) {
            return true;
        }
        while (t != null) {
            if (t instanceof SQLException sqlEx) {
                int err = sqlEx.getErrorCode();
                String state = sqlEx.getSQLState();
                if (err == 1213 || err == 1205 || "40001".equals(state) || "41000".equals(state)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
