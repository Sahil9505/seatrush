-- H2-compatible schema for integration tests (mirrors V1__init.sql with H2 syntax)

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(150) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(10)  NOT NULL DEFAULT 'USER',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS stations (
  id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(10)  NOT NULL,
  name VARCHAR(100) NOT NULL,
  city VARCHAR(100) NOT NULL,
  CONSTRAINT uq_stations_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS trains (
  id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  number VARCHAR(20)  NOT NULL,
  name   VARCHAR(100) NOT NULL,
  CONSTRAINT uq_trains_number UNIQUE (number)
);

CREATE TABLE IF NOT EXISTS train_stops (
  train_id              BIGINT NOT NULL,
  stop_index            INT    NOT NULL,
  station_id            BIGINT NOT NULL,
  arrival_offset_min    INT,
  departure_offset_min  INT,
  distance_km           INT    NOT NULL DEFAULT 0,
  PRIMARY KEY (train_id, stop_index),
  CONSTRAINT fk_ts_train   FOREIGN KEY (train_id)  REFERENCES trains(id),
  CONSTRAINT fk_ts_station FOREIGN KEY (station_id) REFERENCES stations(id)
);

CREATE TABLE IF NOT EXISTS seats (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  train_id  BIGINT      NOT NULL,
  coach     VARCHAR(10) NOT NULL,
  seat_no   INT         NOT NULL,
  seat_type VARCHAR(10) NOT NULL,
  CONSTRAINT fk_seats_train FOREIGN KEY (train_id) REFERENCES trains(id)
);

CREATE TABLE IF NOT EXISTS journeys (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  train_id         BIGINT      NOT NULL,
  travel_date      DATE        NOT NULL,
  booking_opens_at TIMESTAMP   NOT NULL,
  status           VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  CONSTRAINT fk_journeys_train FOREIGN KEY (train_id) REFERENCES trains(id)
);

CREATE TABLE IF NOT EXISTS bookings (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  pnr              VARCHAR(20)    NOT NULL,
  user_id          BIGINT         NOT NULL,
  journey_id       BIGINT         NOT NULL,
  from_stop_index  INT            NOT NULL,
  to_stop_index    INT            NOT NULL,
  status           VARCHAR(20)    NOT NULL,
  seat_id          BIGINT,
  price            DECIMAL(10,2),
  hold_expires_at  TIMESTAMP,
  idempotency_key  VARCHAR(64),
  created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_bookings_pnr UNIQUE (pnr),
  CONSTRAINT fk_bookings_user    FOREIGN KEY (user_id)    REFERENCES users(id),
  CONSTRAINT fk_bookings_journey FOREIGN KEY (journey_id) REFERENCES journeys(id),
  CONSTRAINT fk_bookings_seat    FOREIGN KEY (seat_id)    REFERENCES seats(id)
);

CREATE INDEX IF NOT EXISTS idx_bookings_journey_status_created ON bookings (journey_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings (user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_hold_expires ON bookings (hold_expires_at);

CREATE TABLE IF NOT EXISTS seat_allocations (
  journey_id    BIGINT NOT NULL,
  seat_id       BIGINT NOT NULL,
  segment_index INT    NOT NULL,
  booking_id    BIGINT NOT NULL,
  PRIMARY KEY (journey_id, seat_id, segment_index),
  CONSTRAINT fk_sa_journey FOREIGN KEY (journey_id) REFERENCES journeys(id),
  CONSTRAINT fk_sa_seat    FOREIGN KEY (seat_id)    REFERENCES seats(id),
  CONSTRAINT fk_sa_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
  idem_key        VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id         BIGINT      NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  response_status INT,
  response_body   TEXT,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ik_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS payments (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_id BIGINT         NOT NULL,
  amount     DECIMAL(10,2)  NOT NULL,
  status     VARCHAR(20)    NOT NULL,
  created_at TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);

CREATE TABLE IF NOT EXISTS booking_events (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_id  BIGINT      NOT NULL,
  from_status VARCHAR(20),
  to_status   VARCHAR(20) NOT NULL,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_be_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);
