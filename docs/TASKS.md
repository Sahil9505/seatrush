# Tasks (one task = one AI session. Tick when done, then update PROGRESS.md)
Course-version tasks T01-T08 are done and tagged v1-course-registration.
Strongest model: B01, B03, B05, B07, B08, K2. Cheaper model for the rest.

## Phase 0-1: Pivot and data model (SPEC 5, 14)
- [x] P01 Pivot docs: SPEC.md, AGENTS.md, TASKS.md, PROGRESS.md in place; old docs moved to docs/archive/; D11-D13 appended.
- [ ] P02 Squash schema and rename: replace V1__init.sql with the SPEC section 5 schema, new entities and repositories,
      mechanical rename Student -> User in auth code and tests (drop rollNo, schema has none), delete course-only code
      and tests (sections, registrations, takeSeat/releaseSeat, old waitlist, catalog, booking), update H2 schema.sql.
      Done when: app starts, Flyway applies the new V1, context-load test and AuthIntegrationTest pass.
- [ ] P03 Folded into P02. Tick it together with P02.
- [ ] P04 Seed (dev profile): 6 stations, 2 trains with stops, 72 seats each, journeys, 1 admin, 5,000 users
      (one shared BCrypt hash, batch inserts, idempotent). Done when: SeedTest counts pass.

## Phase 2: The heart (SPEC 4)
- [ ] B01 Pure bitmask functions (segment mask for a to b, overlap check, set and clear) with unit and
      property tests. No Spring, no DB, no Redis. Finish and pass before any B02+.

## Phase 3: Allocation and booking (SPEC 6, 7, 8)
- [ ] B02 Naive booking (read, check, write, no locking) in a test-only variant, plus a MySQL test that
      shows double allocation. This is the "before" in the before/after story.
- [ ] B03 DB-correct booking: HELD booking and seat_allocations rows in one transaction, unique-constraint
      safety net, conflict -> clean 409. Concurrency test on MySQL (profile mysqltest).
- [ ] B04 Idempotency (header, store, replay, 422 on body mismatch).
- [ ] B05 Redis: per-journey seat-mask hash, Lua allocate and release, compensation when the DB insert fails,
      reconcile on startup and via admin endpoint.
- [ ] K1 First k6 run: opening-minute rush, 2,000 users, with verify.sql (zero overlapping allocations).
      Record naive vs DB-correct vs Redis-gated in docs/RESULTS.md.

## Phase 4: Payment and expiry (SPEC 7)
- [ ] B06 Mock payment (configurable latency and failure rate), pay and confirm (conditional UPDATE), 410 HOLD_EXPIRED.
- [ ] B07 Cancel (idempotent) and the hold sweeper (holds:expiry sorted set, DB/Redis time only).

## Phase 5: Waitlist and protection (SPEC 9, 6)
- [ ] B08 Waitlist and fit-aware promotion triggered by cancel and expiry. State the fairness rule in DECISIONS.md.
- [ ] B09 Rate limits (per user and per IP) and the booking_opens_at gate.

## Phase 6-7: Search, frontend, admin (SPEC 10, 11)
- [ ] F1 Search endpoint (trains between two stations on a date, with availability) and booking endpoints per SPEC 10.
- [ ] F2 React: login, search, results.
- [ ] F3 React: seat/booking screen with hold countdown, pay, my bookings.
- [ ] F4 Admin endpoints and dashboard (trains, journeys, stats, reconcile).

## Phase 8: Proof (SPEC 12, 15)
- [ ] K2 Final k6 scenarios: opening rush, retry storm, cancel-and-promote, chaos (Redis restart mid-test),
      invariant SQL after each. Results table in README.

## Phase 9: Ship
- [ ] D1 Docker Compose for the full stack, deploy, README with architecture diagram, results table, how to run.
