# P02 Execution & Troubleshooting Report: Train Schema Squash

This report documents the errors encountered during the execution and verification of Task **P02** (squashing the course-registration database schema into the train seat booking engine model) and how each was resolved.

---

## 1. Summary of Work Done

- **Migration Squash:** Replaced `V1__init.sql` and test `schema.sql` with the 11 tables and indexes specified in **SPEC §5** (`users`, `stations`, `trains`, `train_stops`, `seats`, `journeys`, `bookings`, `seat_allocations`, `idempotency_keys`, `payments`, `booking_events`).
- **Entity & Repository Layer:** Created 15 JPA entity and enum files, and 11 corresponding Spring Data repositories.
- **Auth Layer Refactor:** Mechanically renamed `Student` to `User`, replaced `STUDENT` role with `USER`, updated `AuthService` and `SeatRushUserDetailsService`, dropped obsolete `rollNo`, and updated `AuthIntegrationTest`.
- **Codebase Pruning:** Removed 35 legacy course-registration domain files (entities, repositories, services, controllers, DTOs, seeder, and tests).

---

## 2. Issues Encountered and Resolutions

### Issue 1: Maven Repository File Lock Contention

#### Symptom
When running Maven commands, builds terminated before compiling:
```text
Caused by: java.lang.IllegalStateException: Could not open file channel for 'NamedLockKey{name='file:///Users/sahilsharma/.m2/repository/.locks/artifact~org.springframework.boot~spring-boot-starter-parent~pom~3.3.5.lock', resources=[...]}' after 5 attempts; giving up
```

#### Root Cause
Parallel Maven invocations competed for file-level mutexes in `~/.m2/repository/.locks/`. The lock directory contained stale lock handles from previously backgrounded processes.

#### Resolution
- Purged stale `.locks` files in `~/.m2/repository/.locks/`.
- Executed subsequent test and build commands sequentially.

---

### Issue 2: Flyway Checksum Mismatch on Existing MySQL Database

#### Symptom
During `spring-boot:run`, Spring Boot failed to initialize:
```text
Caused by: org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 1
-> Applied to database : 93515874
-> Resolved locally    : 1789400422
```

#### Root Cause
The active MySQL Docker container (`seatrush-mysql`) already contained the legacy course-registration schema and the original migration history in `flyway_schema_history`. Because P02 was a pivot squash replacing `V1__init.sql`, the existing migration hash in the database did not match the new migration script.

#### Resolution
- Connected to MySQL inside the container (`seatrush-mysql`) and reset the environment:
  ```sql
  DROP DATABASE seatrush;
  CREATE DATABASE seatrush;
  GRANT ALL PRIVILEGES ON seatrush.* TO 'seatrush'@'%';

  DROP DATABASE IF EXISTS seatrush_test;
  CREATE DATABASE seatrush_test;
  GRANT ALL PRIVILEGES ON seatrush_test.* TO 'seatrush'@'%';
  FLUSH PRIVILEGES;
  ```
- Flyway subsequently initialized cleanly on startup and applied the new train model schema from scratch.

---

### Issue 3: Hibernate DDL Type Validation Mismatch on `request_hash`

#### Symptom
With Hibernate `ddl-auto: validate` enabled, application startup failed with:
```text
Caused by: org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: wrong column type encountered in column [request_hash] in table [idempotency_keys]; found [char (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]
```

#### Root Cause
- In `V1__init.sql`, the column was defined as:
  ```sql
  request_hash CHAR(64) NOT NULL
  ```
- In the initial `IdempotencyKey` entity, the property was declared as a plain `String`:
  ```java
  @Column(name = "request_hash", length = 64, nullable = false)
  private String requestHash;
  ```
  By default, Hibernate maps `String` to `Types.VARCHAR`. Under strict DDL validation, Hibernate flagged the discrepancy between MySQL's `CHAR` and the entity's expected `VARCHAR`.

#### Resolution
Added the Hibernate `@JdbcTypeCode(Types.CHAR)` annotation to `IdempotencyKey.java`:
```java
@JdbcTypeCode(Types.CHAR)
@Column(name = "request_hash", length = 64, nullable = false)
private String requestHash;
```
This instructed Hibernate to validate against `Types.CHAR`, resolving the schema mismatch.

---

## 3. Verification Results

Following these fixes:
- **`SeatRushApplicationTests` (H2):** Passed (Spring context loaded cleanly).
- **`AuthIntegrationTest` (H2):** All 6 test cases passed (registration, duplicate validation, login, wrong credentials, and token protection).
- **`spring-boot:run` (MySQL 8 + Flyway):** Successfully applied migration `V1__init.sql`, passed Hibernate strict schema validation for all 11 tables, and initialized Tomcat on the designated port.
