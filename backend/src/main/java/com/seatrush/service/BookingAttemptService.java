package com.seatrush.service;

import com.seatrush.dto.BookingResponse;
import com.seatrush.entity.BookingStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class BookingAttemptService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PNR_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final JdbcTemplate jdbcTemplate;

    public BookingAttemptService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private String generatePnr() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PNR_CHARS.charAt(RANDOM.nextInt(PNR_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Executes in ONE new isolated transaction.
     * Inserts the booking (HELD, hold_expires_at via TIMESTAMPADD(MINUTE, 10, NOW()) in SQL),
     * then inserts the seat_allocations rows in ASCENDING segment_index order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingResponse tryAllocate(
            long journeyId,
            long userId,
            int fromStopIndex,
            int toStopIndex,
            long seatId,
            String coach,
            int seatNo,
            BigDecimal price
    ) {
        String pnr = generatePnr();
        KeyHolder keyHolder = new GeneratedKeyHolder();

        String insertBookingSql =
            "INSERT INTO bookings (pnr, user_id, journey_id, from_stop_index, to_stop_index, status, seat_id, price, hold_expires_at) " +
            "VALUES (?, ?, ?, ?, ?, 'HELD', ?, ?, TIMESTAMPADD(MINUTE, 10, NOW()))";

        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                insertBookingSql,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, pnr);
            ps.setLong(2, userId);
            ps.setLong(3, journeyId);
            ps.setInt(4, fromStopIndex);
            ps.setInt(5, toStopIndex);
            ps.setLong(6, seatId);
            ps.setBigDecimal(7, price);
            return ps;
        }, keyHolder);

        long bookingId;
        if (!keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number num = null;
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number n) {
                    num = n;
                    break;
                }
            }
            bookingId = num != null ? num.longValue() : keyHolder.getKey().longValue();
        } else {
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("Failed to retrieve generated booking ID");
            }
            bookingId = key.longValue();
        }

        // Insert seat_allocations rows in strictly ASCENDING segment_index order
        for (int seg = fromStopIndex; seg < toStopIndex; seg++) {
            jdbcTemplate.update(
                "INSERT INTO seat_allocations (journey_id, seat_id, segment_index, booking_id) VALUES (?, ?, ?, ?)",
                journeyId, seatId, seg, bookingId
            );
        }

        LocalDateTime holdExpiresAt = jdbcTemplate.queryForObject(
            "SELECT hold_expires_at FROM bookings WHERE id = ?",
            (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("hold_expires_at");
                return ts != null ? ts.toLocalDateTime() : null;
            },
            bookingId
        );

        return new BookingResponse(
            bookingId,
            pnr,
            BookingStatus.HELD,
            seatId,
            coach,
            seatNo,
            holdExpiresAt,
            price
        );
    }
}
