# Decisions (append only. Do not re-debate.)

D1. MySQL is the source of truth; Redis is a fast gate in front of it.
    Why: Redis can restart or drift. DB constraints guarantee correctness.
D2. Seat allocation in SQL: UPDATE sections SET seats_taken = seats_taken + 1
    WHERE id = ? AND seats_taken < capacity. 0 rows affected = section full.
    Why: atomic, no explicit locks needed, correct under any concurrency.
D3. UNIQUE (student_id, section_id) on registrations.
    Re-registering after a drop REUSES the existing row (status DROPPED -> CONFIRMED).
D4. Build DB-only version first, measure baseline with k6, then add Redis and re-measure.
    Why: before/after numbers are the CV proof.
D5. Idempotency key scope = (student_id, Idempotency-Key). Same key + different request
    body = 422. Same key + same body = stored response replayed.
D6. Waitlist ordered by created_at (ms precision). Promotion happens inside the same
    DB transaction as the drop.
D7. Auth: JWT, roles STUDENT and ADMIN.
D8. Redis seat counters are rebuilt from MySQL on startup and via an admin endpoint.
D10. takeSeat runs BEFORE the registration insert. Reason: InnoDB FK check takes an S lock on the sections row; a later UPDATE needs X; concurrent S->X upgrades deadlock. Supersedes the ordering reasoning in T06.

