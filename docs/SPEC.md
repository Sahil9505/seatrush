<!-- Pivot note: SeatRush started as a course registration system (tagged v1-course-registration, tasks T01-T08 done). This spec is now the single source of truth. Read only the sections a task needs. -->

# Train Seat Booking Engine: Complete Project Spec

A high-concurrency train seat booking backend with **segment-based seat availability**, idempotent requests, payment holds, a fit-aware waitlist, and a load test that proves zero double allocation. Stack: Spring Boot, MySQL, Redis (Lua), React, k6, Docker.

*This document is written so it can be pasted into a coding assistant as the single source of truth. Fixed decisions are marked [FIXED].*

## 1. Goal and what the project proves

Thousands of users try to book a limited number of seats the moment booking opens. The system must never allocate the same seat for overlapping parts of a route, must survive retries and double-clicks, and must stay fast under load.

What it demonstrates in interviews:

- Concurrency control (atomic Redis Lua + DB unique-constraint safety net)

- Idempotent API design

- Interval-overlap logic with bit manipulation (a real DSA-to-system link)

- Failure handling: hold expiry, payment failures, Redis restart, reconciliation

- Measured results: throughput, p95 latency, zero double allocations

## 2. Scope

**In scope (v1):**

- One route with about 6 stations; 1 to 2 trains; one class; around 72 seats per train

- Search trains between two stations on a date

- Book one passenger per booking, for a from-station to to-station range

- Temporary seat hold with timeout, then mock payment, then confirmation

- Cancellation, and waitlist with automatic promotion

- Admin: create journeys, open booking, view occupancy and queue stats

- Booking-open time (the opening-minute rush)

**Out of scope for v1 (possible later):** Tatkal and quotas, RAC, multiple classes, multi-passenger bookings, real payments, real IRCTC data. Use synthetic data only.

## 3. Tech stack [FIXED]

- Backend: Spring Boot 3, Java 17+, Spring Data JPA, Flyway migrations

- Database: MySQL 8 (source of truth)

- Cache and atomic gate: Redis 7 with Lua scripts

- Frontend: React (search, booking, my bookings, admin dashboard)

- Load testing: k6

- Packaging: Docker Compose (app, MySQL, Redis); simple deploy afterwards

## 4. Core idea: segment availability with bitmasks [FIXED]

A journey has **N stops** (index 0 to N-1), so it has **N-1 segments** (segment i is the stretch between stop i and stop i+1).

A booking from stop a to stop b (a < b) occupies segments a through b-1.

- Request mask: reqMask = ((1 << (b - a)) - 1) << a

- Seat is free for the request if (seatMask & reqMask) == 0

- Book: seatMask |= reqMask

- Cancel: seatMask &= ~reqMask

**Worked example** (6 stops S0 to S5, 5 segments):

- Existing booking S0→S2 uses segments 0 and 1, so mask = 00011 (3)

- Request S2→S4 uses segments 2 and 3, so mask = 01100 (12). No overlap, so the same seat can be sold.

- Request S1→S3 uses segments 1 and 2, so mask = 00110 (6). Overlaps on segment 1, so this seat is rejected.

This lets one physical seat serve several passengers on one journey, which is the main difference from a simple seat counter.

## 5. Data model (MySQL)

| Table | Key columns |
| --- | --- |
| users | id, name, email (unique), password_hash, role (USER/ADMIN), created_at |
| stations | id, code (unique), name, city |
| trains | id, number (unique), name |
| train_stops | train_id, stop_index, station_id, arrival_offset_min, departure_offset_min, distance_km |
| seats | id, train_id, coach, seat_no, seat_type |
| journeys | id, train_id, travel_date, booking_opens_at, status (SCHEDULED/OPEN/DEPARTED/CANCELLED) |
| bookings | id, pnr (unique), user_id, journey_id, from_stop_index, to_stop_index, status, seat_id (nullable), price, hold_expires_at, idempotency_key, created_at, updated_at |
| seat_allocations | journey_id, seat_id, segment_index, booking_id, **UNIQUE(journey_id, seat_id, segment_index)** |
| idempotency_keys | key, user_id, request_hash, response_status, response_body, created_at |
| payments (mock) | id, booking_id, amount, status, created_at |
| booking_events | id, booking_id, from_status, to_status, at (audit trail, optional but useful) |

**Booking statuses [FIXED]:** HELD, CONFIRMED, WAITLISTED, EXPIRED, CANCELLED.

Indexes: bookings(journey_id, status, created_at) for waitlist ordering; bookings(user_id); bookings(hold_expires_at) for the sweeper.

**Safety-net rule [FIXED]:** seat_allocations stores one row per segment per seat per journey, with a unique constraint. Even if Redis has a bug, MySQL physically cannot store a double allocation. A booking of a→b inserts b-a rows in one transaction.

## 6. Architecture: Redis as the fast gate, MySQL as the truth [FIXED]

- **Redis** holds, per journey, a hash journey:{id}:seats mapping seatId to the current segment bitmask. A Lua script scans seats, finds the first whose mask does not overlap the request, sets the bits, and returns the seat id (or -1 if none fit). Redis runs scripts atomically, so two requests can never take the same seat.

- **MySQL** records the booking and the seat_allocations rows in a transaction. If the insert fails (unique violation), the Redis allocation is rolled back by a release script and the request retries or fails safely.

- **On startup (and via an admin endpoint)**, a reconciliation job rebuilds Redis masks from seat_allocations, and reports any mismatch it finds.

- **Opening-minute protection:** per-user and per-IP rate limits; requests before booking_opens_at are rejected with a clear error.

Start with a deliberately naive version (read, check, write with no locking) and let the load test show double bookings. Then fix it. That before-and-after is one of the best proof points for interviews.

## 7. Booking flow

- Client sends POST /api/bookings with an Idempotency-Key header and body {journeyId, fromStopIndex, toStopIndex}.

- Idempotency check: see section 8.

- Validate: journey open, from < to, journey not departed, user has no overlapping active booking on this journey.

- Run the Lua allocation script.

- Seat found: create the booking as HELD, hold_expires_at = now + 10 min, insert seat_allocations rows, add the hold to a Redis sorted set holds:expiry (score = expiry time).

- No seat found: create the booking as WAITLISTED (if the waitlist is under its cap) or return 409 SOLD_OUT.

- POST /api/bookings/{id}/pay runs the mock payment (configurable latency and failure rate). Success moves HELD to CONFIRMED with a conditional update: UPDATE ... WHERE id=? AND status='HELD' AND hold_expires_at > NOW(). If zero rows change, return 410 HOLD_EXPIRED.

- **Hold sweeper:** a scheduled job pops expired holds from holds:expiry, marks the booking EXPIRED, deletes its seat_allocations, runs the release Lua script, and triggers waitlist promotion. Do not rely on Redis key-expiry notifications; they are not reliable enough.

- POST /api/bookings/{id}/cancel releases the seat the same way and triggers promotion. Cancelling twice is harmless.

Use database or Redis time, not the client clock, for all expiry decisions.

## 8. Idempotency [FIXED]

- Every booking and payment request carries Idempotency-Key.

- Take a short Redis lock SET idem:{key} NX PX 30000 for in-flight requests.

- Same key and same payload: return the stored response, with no second booking.

- Same key and a different payload: 422.

- Store the final response in idempotency_keys so retries after the lock expires still work.

## 9. Waitlist and fit-aware promotion

A waitlisted request has its own from and to stops, so when a seat frees up, the next person in line may not fit it. Rules:

- Waitlist order is FIFO by created_at.

- When segments of seat S free up on journey J, only seat S changed, so check only S.

- Walk the waitlist in order, and promote the first entry whose reqMask fits the freed segments. Then continue walking with the remaining free segments, because several people might fit.

- Entries that do not fit are skipped but **keep their place**.

- A per-journey Redis lock (SET promo:{journeyId} NX PX 5000) ensures only one promotion run per journey at a time.

- A promoted booking becomes CONFIRMED (payment is mocked and assumed captured at join time; refund is mocked if never promoted).

- Make the policy explicit in the README: strict FIFO versus skip-if-no-fit is a trade-off, and you chose skip-if-no-fit for utilisation.

## 10. API

Auth: POST /api/auth/register, POST /api/auth/login (JWT).

Public and user endpoints:

- GET /api/stations

- GET /api/journeys?from=&to=&date= returns trains with available seat count for that range

- GET /api/journeys/{id}/availability?from=&to=

- POST /api/bookings (Idempotency-Key required)

- POST /api/bookings/{id}/pay (Idempotency-Key required)

- POST /api/bookings/{id}/cancel

- GET /api/bookings/me, GET /api/bookings/{pnr}

Admin:

- POST /api/admin/trains, POST /api/admin/journeys

- POST /api/admin/journeys/{id}/open (loads seat masks into Redis)

- GET /api/admin/journeys/{id}/stats (occupancy, held, waitlist depth, booking rate)

- POST /api/admin/journeys/{id}/reconcile

Error codes: 400 invalid range, 403 booking not open, 409 sold out or conflicting booking, 410 hold expired, 422 idempotency conflict, 429 rate limited.

## 11. Frontend screens

- Search: from, to, date

- Results: trains with live availability and a booking-opens countdown

- Booking: confirm range and price, hold countdown timer, pay button

- My bookings: status, PNR, cancel, waitlist position

- Admin dashboard: occupancy per journey, live booking rate, waitlist depth, reconcile button

## 12. Load testing and proof (k6)

| Scenario | What it does | What must be true |
| --- | --- | --- |
| Opening rush | 2,000 virtual users hit one journey at the same moment with overlapping ranges | No seat-segment is allocated twice; confirmed plus held plus waitlisted equals accepted requests |
| Retry storm | The same Idempotency-Key sent 3 to 5 times concurrently | Exactly one booking exists |
| Cancel and promote | Random cancellations while a waitlist exists | Promotions follow FIFO-with-fit; no double allocation |
| Chaos | Restart Redis and the app mid-test; mock payment timeouts | Holds expire and release; reconciliation rebuilds Redis; ledger of allocations stays consistent |

After each run, run SQL invariant checks: no duplicate (journey, seat, segment), Redis masks equal MySQL allocations, and every CONFIRMED booking has exactly to - from allocation rows.

Report: p50, p95, p99 latency of POST /api/bookings, requests per second, error rate, and utilisation (segments sold divided by total segments). Record your hardware and settings. Only put numbers on your CV that you measured yourself.

## 13. Build order and time estimate

| Phase | Work | Estimate |
| --- | --- | --- |
| 0 | Freeze this spec; list which existing tasks are kept, modified or discarded | 0.5 day |
| 1 | Data model, Flyway migrations, seed data (6 stations, 2 trains, 72 seats each) | 2 days |
| 2 | Pure bitmask availability functions with unit and property tests (build this first, it is the heart) | 1 to 2 days |
| 3 | Redis Lua allocation and release, seat_allocations transaction, HELD flow, idempotency | 3 days |
| 4 | Mock payment, confirm, cancel, hold-expiry sweeper | 2 days |
| 5 | Waitlist and fit-aware promotion | 2 days |
| 6 | Search endpoint and React screens | 3 days |
| 7 | Admin dashboard and stats | 1 to 2 days |
| 8 | k6 scenarios, naive-versus-fixed comparison, chaos test, reconciliation | 3 days |
| 9 | Docker Compose, deploy, README with architecture diagram and results | 2 days |

Total is roughly 3.5 to 4.5 weeks of part-time work for a fresh start; with about 9 of 24 earlier tasks reusable, expect about 1 to 1.5 weeks of extra work beyond the original plan.

## 14. Migrating from the course registration version

**Keep:** auth and users, React shell, idempotency handling, Redis request queue, rate limiting, k6 setup, concurrency tests, base waitlist structure.

**Modify:** course becomes train, section becomes journey, enrollment becomes booking, the seat counter becomes the seat bitmask, the waitlist becomes fit-aware, and admin course management becomes admin trains and journeys.

**Discard:** anything that assumes one seat equals one student for the whole offering.

Refactor the data model first, while less is built on it.

## 15. Edge cases and test checklist

- From stop not before to stop; unknown stops

- Booking before opening time or after departure

- Same user booking overlapping ranges on the same journey

- Paying after the hold expired (must return 410, never confirm)

- Payment succeeds at the same instant the sweeper expires the hold (conditional update decides the winner)

- Cancelling twice; cancelling a promoted waitlist booking

- Redis restart losing masks (reconciliation rebuilds)

- Two cancellations triggering promotion simultaneously (per-journey lock)

- Clock differences (use server-side time only)

## 16. Interview talking points

- Why a bitmask per seat, and how it generalises to partial routes

- Why Redis Lua for atomicity, and why MySQL still has the final say through the unique constraint

- Why not only SELECT ... FOR UPDATE: lock contention under the opening rush (show your measured comparison)

- Idempotency design and failure modes

- Waitlist fairness trade-off: strict FIFO versus skip-if-no-fit

- What you would change at 100x scale (partition by journey, sharded Redis, event-driven promotion)

## 17. Resume bullet templates (fill in only measured values)

- Built a train seat booking engine (Spring Boot, MySQL, Redis) using bitmask-based segment availability so one seat serves multiple passengers on one journey; sustained **[X]** concurrent users with **zero** double allocations.

- Achieved p95 latency of **[X] ms** at **[Y] requests/second** on POST /bookings, verified with k6 against a naive baseline that produced **[Z]** double bookings.

- Designed idempotent booking and payment APIs, hold expiry, and fit-aware waitlist promotion; added reconciliation that rebuilds Redis state from MySQL after failure.

## 18. Instructions for the AI coding assistant

- Treat this document as the single source of truth; do not re-scan the whole repository for each task.

- Work one phase at a time, and only open files relevant to that phase.

- Do not change anything marked **[FIXED]** without asking.

- Write tests with each phase; finish phase 2 (bitmask logic) with passing tests before starting phase 3.

- Keep responses short and code-focused; no re-explaining decisions already written here.
