package com.seatrush.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    public void run(String... args) {
        seedDevData();
    }

    @Transactional
    public void seedDevData() {
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class);

        if (userCount != null && userCount > 0) {
            log.info("Database already seeded ({} users found). Skipping seeding.", userCount);
            return;
        }

        log.info("Seeding dev database...");
        long startTime = System.currentTimeMillis();

        // 1. Stations: 6 on one route
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('NDLS', 'New Delhi', 'New Delhi')");
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('CNB', 'Kanpur Central', 'Kanpur')");
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('PRYJ', 'Prayagraj Junction', 'Prayagraj')");
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('DDU', 'Pt. Deen Dayal Upadhyaya Junction', 'Mughalsarai')");
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('PNBE', 'Patna Junction', 'Patna')");
        jdbcTemplate.update("INSERT INTO stations (code, name, city) VALUES ('HWH', 'Howrah Junction', 'Kolkata')");

        Long ndlsId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'NDLS'", Long.class);
        Long cnbId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'CNB'", Long.class);
        Long pryjId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'PRYJ'", Long.class);
        Long dduId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'DDU'", Long.class);
        Long pnbeId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'PNBE'", Long.class);
        Long hwhId = jdbcTemplate.queryForObject("SELECT id FROM stations WHERE code = 'HWH'", Long.class);

        Long[] stationIds = new Long[]{ndlsId, cnbId, pryjId, dduId, pnbeId, hwhId};

        // 2. Trains: 2 trains both running the full route in the same direction
        jdbcTemplate.update("INSERT INTO trains (number, name) VALUES ('12301', 'Rajdhani Express')");
        jdbcTemplate.update("INSERT INTO trains (number, name) VALUES ('12302', 'Vande Bharat Express')");

        Long train1Id = jdbcTemplate.queryForObject("SELECT id FROM trains WHERE number = '12301'", Long.class);
        Long train2Id = jdbcTemplate.queryForObject("SELECT id FROM trains WHERE number = '12302'", Long.class);
        Long[] trainIds = new Long[]{train1Id, train2Id};

        // 3. Train stops: 6 stops per train (stop_index 0..5), total 12
        int[][] train1Schedule = new int[][]{
                // arrival_offset_min, departure_offset_min, distance_km
                {0, 0, 0},
                {240, 245, 440},
                {360, 365, 635},
                {480, 490, 788},
                {630, 640, 998},
                {990, 990, 1445}
        };

        int[][] train2Schedule = new int[][]{
                {0, 0, 0},
                {270, 275, 440},
                {400, 405, 635},
                {520, 530, 788},
                {680, 690, 998},
                {1050, 1050, 1445}
        };

        for (int i = 0; i < 6; i++) {
            jdbcTemplate.update(
                    "INSERT INTO train_stops (train_id, stop_index, station_id, arrival_offset_min, departure_offset_min, distance_km) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    train1Id, i, stationIds[i], train1Schedule[i][0], train1Schedule[i][1], train1Schedule[i][2]
            );
            jdbcTemplate.update(
                    "INSERT INTO train_stops (train_id, stop_index, station_id, arrival_offset_min, departure_offset_min, distance_km) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    train2Id, i, stationIds[i], train2Schedule[i][0], train2Schedule[i][1], train2Schedule[i][2]
            );
        }

        // 4. Seats: 72 per train (4 coaches x 18 seats), total 144 seats
        String[] coaches = new String[]{"C1", "C2", "C3", "C4"};
        List<Object[]> seatRows = new ArrayList<>(144);
        for (Long tId : trainIds) {
            for (String coach : coaches) {
                for (int seatNo = 1; seatNo <= 18; seatNo++) {
                    String seatType;
                    int rem = seatNo % 3;
                    if (rem == 1) {
                        seatType = "WINDOW";
                    } else if (rem == 2) {
                        seatType = "MIDDLE";
                    } else {
                        seatType = "AISLE";
                    }
                    seatRows.add(new Object[]{tId, coach, seatNo, seatType});
                }
            }
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO seats (train_id, coach, seat_no, seat_type) VALUES (?, ?, ?, ?)",
                seatRows,
                144,
                (ps, row) -> {
                    ps.setLong(1, (Long) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setInt(3, (Integer) row[2]);
                    ps.setString(4, (String) row[3]);
                }
        );

        // 5. Journeys: 7 days per train (total 14 journeys), status OPEN,
        // booking_opens_at in past except the last day in future
        LocalDate today = LocalDate.now();
        List<Object[]> journeyRows = new ArrayList<>(14);
        for (Long tId : trainIds) {
            for (int day = 1; day <= 7; day++) {
                LocalDate travelDate = today.plusDays(day);
                LocalDateTime bookingOpensAt = (day == 7)
                        ? LocalDateTime.now().plusDays(1)
                        : LocalDateTime.now().minusDays(1);

                journeyRows.add(new Object[]{
                        tId,
                        Date.valueOf(travelDate),
                        Timestamp.valueOf(bookingOpensAt),
                        "OPEN"
                });
            }
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO journeys (train_id, travel_date, booking_opens_at, status) VALUES (?, ?, ?, ?)",
                journeyRows,
                14,
                (ps, row) -> {
                    ps.setLong(1, (Long) row[0]);
                    ps.setDate(2, (Date) row[1]);
                    ps.setTimestamp(3, (Timestamp) row[2]);
                    ps.setString(4, (String) row[3]);
                }
        );

        // 6. Users: 1 admin + 5,000 users, total 5,001 users
        String passwordHash = passwordEncoder.encode("password123");

        jdbcTemplate.update(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, ?)",
                "Admin", "admin@seatrush.test", passwordHash, "ADMIN"
        );

        List<Object[]> userRows = new ArrayList<>(5000);
        for (int i = 1; i <= 5000; i++) {
            userRows.add(new Object[]{
                    String.format("User %04d", i),
                    String.format("user%04d@seatrush.test", i),
                    passwordHash,
                    "USER"
            });
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, ?)",
                userRows,
                1000,
                (ps, row) -> {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                }
        );

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Dev seed completed in {} ms (6 stations, 2 trains, 12 stops, 144 seats, 14 journeys, 5001 users).", elapsed);
    }
}
