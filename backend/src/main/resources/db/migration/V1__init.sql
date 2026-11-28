-- SeatRush train-booking schema (pivot squash from course-registration).
-- Safe only on a fresh database or after dropping all old tables.

CREATE TABLE users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(150) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(10)  NOT NULL DEFAULT 'USER',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB;

CREATE TABLE stations (
  id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(10)  NOT NULL,
  name VARCHAR(100) NOT NULL,
  city VARCHAR(100) NOT NULL,
  UNIQUE KEY uq_stations_code (code)
) ENGINE=InnoDB;

CREATE TABLE trains (
  id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  number VARCHAR(20)  NOT NULL,
  name   VARCHAR(100) NOT NULL,
  UNIQUE KEY uq_trains_number (number)
) ENGINE=InnoDB;

CREATE TABLE train_stops (
  train_id              BIGINT NOT NULL,
  stop_index            INT    NOT NULL,
  station_id            BIGINT NOT NULL,
  arrival_offset_min    INT,
  departure_offset_min  INT,
  distance_km           INT    NOT NULL DEFAULT 0,
  PRIMARY KEY (train_id, stop_index),
  CONSTRAINT fk_ts_train   FOREIGN KEY (train_id)  REFERENCES trains(id),
  CONSTRAINT fk_ts_station FOREIGN KEY (station_id) REFERENCES stations(id)
) ENGINE=InnoDB;

CREATE TABLE seats (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  train_id  BIGINT      NOT NULL,
  coach     VARCHAR(10) NOT NULL,
  seat_no   INT         NOT NULL,
  seat_type VARCHAR(10) NOT NULL,
  CONSTRAINT fk_seats_train FOREIGN KEY (train_id) REFERENCES trains(id)
) ENGINE=InnoDB;

CREATE TABLE journeys (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  train_id         BIGINT      NOT NULL,
  travel_date      DATE        NOT NULL,
  booking_opens_at DATETIME    NOT NULL,
  status           VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  CONSTRAINT fk_journeys_train FOREIGN KEY (train_id) REFERENCES trains(id)
) ENGINE=InnoDB;

CREATE TABLE bookings (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  pnr              VARCHAR(20)    NOT NULL,
  user_id          BIGINT         NOT NULL,
  journey_id       BIGINT         NOT NULL,
  from_stop_index  INT            NOT NULL,
  to_stop_index    INT            NOT NULL,
  status           VARCHAR(20)    NOT NULL,
  seat_id          BIGINT,
  price            DECIMAL(10,2),
  hold_expires_at  DATETIME,
  idempotency_key  VARCHAR(64),
  created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_bookings_pnr (pnr),
  KEY idx_bookings_journey_status_created (journey_id, status, created_at),
  KEY idx_bookings_user (user_id),
  KEY idx_bookings_hold_expires (hold_expires_at),
  CONSTRAINT fk_bookings_user    FOREIGN KEY (user_id)    REFERENCES users(id),
  CONSTRAINT fk_bookings_journey FOREIGN KEY (journey_id) REFERENCES journeys(id),
  CONSTRAINT fk_bookings_seat    FOREIGN KEY (seat_id)    REFERENCES seats(id)
) ENGINE=InnoDB;

CREATE TABLE seat_allocations (
  journey_id    BIGINT NOT NULL,
  seat_id       BIGINT NOT NULL,
  segment_index INT    NOT NULL,
  booking_id    BIGINT NOT NULL,
  PRIMARY KEY (journey_id, seat_id, segment_index),
  CONSTRAINT fk_sa_journey FOREIGN KEY (journey_id) REFERENCES journeys(id),
  CONSTRAINT fk_sa_seat    FOREIGN KEY (seat_id)    REFERENCES seats(id),
  CONSTRAINT fk_sa_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_keys (
  idem_key        VARCHAR(64)  NOT NULL PRIMARY KEY,
  user_id         BIGINT       NOT NULL,
  request_hash    CHAR(64)     NOT NULL,
  response_status INT,
  response_body   TEXT,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ik_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE payments (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_id BIGINT         NOT NULL,
  amount     DECIMAL(10,2)  NOT NULL,
  status     VARCHAR(20)    NOT NULL,
  created_at TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
) ENGINE=InnoDB;

CREATE TABLE booking_events (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_id  BIGINT      NOT NULL,
  from_status VARCHAR(20),
  to_status   VARCHAR(20) NOT NULL,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_be_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
) ENGINE=InnoDB;
