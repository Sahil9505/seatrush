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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingAttemptService {

    private final JdbcTemplate jdbcTemplate;

    public BookingAttemptService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private volatile Boolean isH2 = null;

    private boolean isH2Database() {
        if (isH2 == null) {
            synchronized (this) {
                if (isH2 == null) {
                    try {
                        String dbName = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con ->
                            con.getMetaData().getDatabaseProductName()
                        );
                        isH2 = dbName != null && dbName.toLowerCase().contains("h2");
                    } catch (Exception e) {
                        isH2 = false;
                    }
                }
            }
        }
        return isH2;
    }

    /**
     * Executes in ONE new isolated transaction.
     * Inserts the booking (HELD, hold_expires_at via DATE_ADD(NOW(), INTERVAL 10 MINUTE) in SQL),
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
        String pnr = "cc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        KeyHolder keyHolder = new GeneratedKeyHolder();

        String dateAddClause = isH2Database()
            ? "DATEADD('MINUTE', 10, NOW())"
            : "DATE_ADD(NOW(), INTERVAL 10 MINUTE)";

        String insertBookingSql =
            "INSERT INTO bookings (pnr, user_id, journey_id, from_stop_index, to_stop_index, status, seat_id, price, hold_expires_at) " +
            "VALUES (?, ?, ?, ?, ?, 'HELD', ?, ?, " + dateAddClause + ")";

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
