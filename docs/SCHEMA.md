# Schema (source: backend/src/main/resources/db/migration). Summary only.

students(id, roll_no UQ, name, email UQ, password_hash, role STUDENT|ADMIN)
courses(id, code UQ, title, credits)
sections(id, course_id FK, section_code, instructor, schedule, capacity, seats_taken, opens_at)
  UQ(course_id, section_code); CHECK 0 <= seats_taken <= capacity
registrations(id, student_id FK, section_id FK, status CONFIRMED|DROPPED, created_at, updated_at)
  UQ(student_id, section_id)
waitlist(id, section_id FK, student_id FK, status WAITING|PROMOTED|CANCELLED, created_at ms)
  UQ(student_id, section_id); idx(section_id, status, created_at)
idempotency_keys(student_id, idem_key PK, request_hash, response_status, response_body, created_at)
audit_log(id, event_type, student_id, section_id, detail, created_at ms)

## Key queries
- Take a seat:
  UPDATE sections SET seats_taken = seats_taken + 1 WHERE id = :id AND seats_taken < capacity;
- Release a seat:
  UPDATE sections SET seats_taken = seats_taken - 1 WHERE id = :id AND seats_taken > 0;
- Verify (after load test; both must return 0 rows):
  SELECT id FROM sections WHERE seats_taken > capacity;
  SELECT section_id, COUNT(*) c FROM registrations WHERE status='CONFIRMED'
    GROUP BY section_id HAVING c > (SELECT capacity FROM sections WHERE id = section_id);
- Consistency (counter matches rows; must return 0 rows):
  SELECT s.id FROM sections s WHERE s.seats_taken <>
    (SELECT COUNT(*) FROM registrations r WHERE r.section_id = s.id AND r.status='CONFIRMED');
