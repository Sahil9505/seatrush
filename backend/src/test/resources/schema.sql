-- H2-compatible schema for integration tests (mirrors V1__init.sql with H2 syntax)
CREATE TABLE IF NOT EXISTS students (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  roll_no       VARCHAR(20)  NOT NULL,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(150) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(10)  NOT NULL DEFAULT 'STUDENT',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_students_roll  UNIQUE (roll_no),
  CONSTRAINT uq_students_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS courses (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  code    VARCHAR(20)  NOT NULL,
  title   VARCHAR(150) NOT NULL,
  credits TINYINT NOT NULL DEFAULT 3,
  CONSTRAINT uq_courses_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sections (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id    BIGINT NOT NULL,
  section_code VARCHAR(10)  NOT NULL,
  instructor   VARCHAR(100),
  schedule     VARCHAR(100),
  capacity     INT NOT NULL,
  seats_taken  INT NOT NULL DEFAULT 0,
  opens_at     TIMESTAMP    NOT NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_sections UNIQUE (course_id, section_code),
  CONSTRAINT fk_sections_course FOREIGN KEY (course_id) REFERENCES courses(id),
  CONSTRAINT chk_seats CHECK (seats_taken >= 0 AND seats_taken <= capacity)
);

CREATE TABLE IF NOT EXISTS registrations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  status     VARCHAR(20)  NOT NULL,
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_reg_student_section UNIQUE (student_id, section_id),
  CONSTRAINT fk_reg_student FOREIGN KEY (student_id) REFERENCES students(id),
  CONSTRAINT fk_reg_section FOREIGN KEY (section_id) REFERENCES sections(id)
);

CREATE TABLE IF NOT EXISTS waitlist (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  section_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  status     VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_wait_student_section UNIQUE (student_id, section_id),
  CONSTRAINT fk_wait_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT fk_wait_student FOREIGN KEY (student_id) REFERENCES students(id)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
  student_id      BIGINT NOT NULL,
  idem_key        VARCHAR(64) NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  response_status INT,
  response_body   TEXT,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (student_id, idem_key)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL,
  student_id BIGINT,
  section_id BIGINT,
  detail     VARCHAR(255),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
