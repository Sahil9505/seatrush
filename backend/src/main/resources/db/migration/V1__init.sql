-- SeatRush initial schema. Do NOT edit after applied; add V2, V3... instead.

CREATE TABLE students (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  roll_no       VARCHAR(20)  NOT NULL,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(150) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          ENUM('STUDENT','ADMIN') NOT NULL DEFAULT 'STUDENT',
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_students_roll (roll_no),
  UNIQUE KEY uq_students_email (email)
) ENGINE=InnoDB;

CREATE TABLE courses (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  code    VARCHAR(20)  NOT NULL,
  title   VARCHAR(150) NOT NULL,
  credits TINYINT NOT NULL DEFAULT 3,
  UNIQUE KEY uq_courses_code (code)
) ENGINE=InnoDB;

CREATE TABLE sections (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id    BIGINT NOT NULL,
  section_code VARCHAR(10) NOT NULL,
  instructor   VARCHAR(100),
  schedule     VARCHAR(100),
  capacity     INT NOT NULL,
  seats_taken  INT NOT NULL DEFAULT 0,
  opens_at     DATETIME NOT NULL,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_sections (course_id, section_code),
  CONSTRAINT fk_sections_course FOREIGN KEY (course_id) REFERENCES courses(id),
  CONSTRAINT chk_seats CHECK (seats_taken >= 0 AND seats_taken <= capacity)
) ENGINE=InnoDB;

CREATE TABLE registrations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  status     ENUM('CONFIRMED','DROPPED') NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_reg_student_section (student_id, section_id),
  KEY idx_reg_section_status (section_id, status),
  CONSTRAINT fk_reg_student FOREIGN KEY (student_id) REFERENCES students(id),
  CONSTRAINT fk_reg_section FOREIGN KEY (section_id) REFERENCES sections(id)
) ENGINE=InnoDB;

CREATE TABLE waitlist (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  section_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  status     ENUM('WAITING','PROMOTED','CANCELLED') NOT NULL DEFAULT 'WAITING',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_wait_student_section (student_id, section_id),
  KEY idx_wait_section_status_created (section_id, status, created_at),
  CONSTRAINT fk_wait_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT fk_wait_student FOREIGN KEY (student_id) REFERENCES students(id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_keys (
  student_id      BIGINT NOT NULL,
  idem_key        VARCHAR(64) NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  response_status INT,
  response_body   TEXT,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (student_id, idem_key)
) ENGINE=InnoDB;

CREATE TABLE audit_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  student_id BIGINT,
  section_id BIGINT,
  detail     VARCHAR(255),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_audit_section (section_id, created_at)
) ENGINE=InnoDB;
