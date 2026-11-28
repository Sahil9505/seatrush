package com.seatrush;

import com.seatrush.availability.SegmentMask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Naive booking baseline with NO locking and NO transaction tricks.
 * Plain test-only class (NOT a Spring bean) for concurrency race demonstration.
 */
public class NaiveBooking {

    public enum Result {
        SUCCESS,
        SOLD_OUT
    }

    private final JdbcTemplate jdbcTemplate;

    public NaiveBooking(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to book all segments from fromStop to toStop naively:
     * 1. Read current occupancy from the allocations table.
     * 2. Find first free seat using SegmentMask.findFirstFreeSeat. If -1, return SOLD_OUT.
     * 3. Insert one allocation row per segment for the chosen seat.
     *    (For seat_allocations, insert a HELD booking row first due to FK; for copy table, no booking row.)
     *
     * No sleeps, no locks, no transaction isolation tricks.
     */
    public Result book(long journeyId, long userId, int fromStop, int toStop, String allocationsTable) {
        // 1. Read current occupancy of the journey: per seat, a mask built from the allocations table.
        List<Long> seatIds = jdbcTemplate.query(
            "SELECT s.id FROM seats s JOIN journeys j ON s.train_id = j.train_id WHERE j.id = ? ORDER BY s.id",
            (rs, rowNum) -> rs.getLong("id"),
            journeyId
        );

        if (seatIds.isEmpty()) {
            return Result.SOLD_OUT;
        }

        Integer stopCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM train_stops ts JOIN journeys j ON ts.train_id = j.train_id WHERE j.id = ?",
            Integer.class,
            journeyId
        );
        if (stopCount == null || stopCount < 2) {
            throw new IllegalStateException("Invalid stop count for journey: " + journeyId);
        }

        long[] seatMasks = new long[seatIds.size()];
        Map<Long, Integer> seatIndexMap = new HashMap<>(seatIds.size());
        for (int i = 0; i < seatIds.size(); i++) {
            seatIndexMap.put(seatIds.get(i), i);
        }

        jdbcTemplate.query(
            "SELECT seat_id, segment_index FROM " + allocationsTable + " WHERE journey_id = ?",
            rs -> {
                long seatId = rs.getLong("seat_id");
                int segmentIndex = rs.getInt("segment_index");
                Integer idx = seatIndexMap.get(seatId);
                if (idx != null && segmentIndex >= 0 && segmentIndex < 63) {
                    seatMasks[idx] |= (1L << segmentIndex);
                }
            },
            journeyId
        );

        // 2. Use SegmentMask.findFirstFreeSeat on those masks. If -1, return SOLD_OUT.
        long reqMask = SegmentMask.requestMask(fromStop, toStop, stopCount);
        int chosenIndex = SegmentMask.findFirstFreeSeat(seatMasks, reqMask);
        if (chosenIndex == -1) {
            return Result.SOLD_OUT;
        }

        long chosenSeatId = seatIds.get(chosenIndex);

        // 3. Insert one allocation row per segment for the chosen seat.
        boolean isRealTable = "seat_allocations".equalsIgnoreCase(allocationsTable);

        if (isRealTable) {
            String pnr = "cc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO bookings (pnr, user_id, journey_id, from_stop_index, to_stop_index, status, seat_id, hold_expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'HELD', ?, DATE_ADD(NOW(), INTERVAL 10 MINUTE))",
                    Statement.RETURN_GENERATED_KEYS
                );
                ps.setString(1, pnr);
                ps.setLong(2, userId);
                ps.setLong(3, journeyId);
                ps.setInt(4, fromStop);
                ps.setInt(5, toStop);
                ps.setLong(6, chosenSeatId);
                return ps;
            }, keyHolder);

            Number generatedKey = keyHolder.getKey();
            if (generatedKey == null) {
                throw new IllegalStateException("Failed to retrieve generated booking ID");
            }
            long bookingId = generatedKey.longValue();

            for (int seg = fromStop; seg < toStop; seg++) {
                jdbcTemplate.update(
                    "INSERT INTO seat_allocations (journey_id, seat_id, segment_index, booking_id) VALUES (?, ?, ?, ?)",
                    journeyId, chosenSeatId, seg, bookingId
                );
            }
        } else {
            String bookingRef = "cc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            for (int seg = fromStop; seg < toStop; seg++) {
                jdbcTemplate.update(
                    "INSERT INTO " + allocationsTable + " (journey_id, seat_id, segment_index, booking_ref) VALUES (?, ?, ?, ?)",
                    journeyId, chosenSeatId, seg, bookingRef
                );
            }
        }

        return Result.SUCCESS;
    }
}
