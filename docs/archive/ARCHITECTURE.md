# Architecture (update only when structure changes)

## Packages (backend/src/main/java/com/seatrush)
- config/      Security, Redis, JWT, CORS, rate-limit config
- controller/  AuthController, CourseController, RegistrationController, WaitlistController, AdminController
- service/     AuthService, CourseService, BookingService (ONLY place that writes bookings),
               WaitlistService, SeatCounterService (Redis), IdempotencyService, AuditService
- repository/  Spring Data JPA repos (+ native queries for conditional UPDATE)
- entity/      Student, Course, Section, Registration, WaitlistEntry, IdempotencyKey, AuditLog
- dto/         request/response objects
- exception/   domain exceptions + global handler (see docs/API.md error codes)

## Frontend (frontend/ , React + Vite)
- pages/student: Courses, MyRegistrations, Waitlist
- pages/admin: Sections, Stats
- api/: axios client that attaches JWT and generates an Idempotency-Key per user action

## Register flow (final design)
1. Auth -> rate limit -> check section.opens_at <= now
2. Idempotency lookup (Redis, then DB). If found: replay stored response.
3. Redis Lua: if seats:{sectionId} > 0 then DECR (else -> full)
4. DB transaction: INSERT/reactivate registration (UNIQUE guards duplicates)
   + conditional UPDATE sections. On any failure: INCR Redis back (compensate).
5. Store idempotency response. Write audit_log. Return 201.
6. If full: client may POST /waitlist.

## Drop + promotion flow
1. DB transaction: registration -> DROPPED, seats_taken - 1
2. Same transaction: pick oldest WAITING entry for section, create/reactivate registration,
   seats_taken + 1, waitlist -> PROMOTED
3. After commit: update Redis counter and waitlist zset. Audit log. (Optional: notify.)

## Redis keys
- seats:{sectionId}          int, seats left
- idem:{studentId}:{key}     string JSON, TTL 24h
- waitlist:{sectionId}       zset (member = studentId, score = join epoch ms)
- rl:{studentId}:{window}    int, rate limit counter, TTL = window

## Phase map
- Phase 1: DB-only booking (baseline). Phase 2: idempotency, Redis gate, waitlist, rate limit.
- Phase 3: frontend, final load test, deploy.
