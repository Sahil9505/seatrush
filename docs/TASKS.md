# Tasks (one task = one AI session. Tick when done, then update PROGRESS.md)

## Phase 0: Setup
- [x] T01 Scaffold Spring Boot 3 backend (web, data-jpa, validation, security, flyway-mysql, mysql, data-redis, actuator, lombok). Config reads docker-compose values.
      Done when: `docker compose up -d` + app start -> /actuator/health is UP, Flyway V1 applied.
- [ ] T02 Entities + repositories for all tables. Done when: context-load test passes.
- [ ] T03 Auth (register/login, JWT, roles). Done when: AuthIntegrationTest passes.
- [ ] T04 Seed runner (dev profile): 50 sections (capacity 30-60), 5,000 students, 1 admin.
      Done when: counts verified by query.

## Phase 1: Correct DB-only booking (baseline)
- [ ] T05 Catalog endpoints (GET /courses, /sections/{id}).
- [ ] T06 BookingService.register() DB-only (conditional UPDATE + UNIQUE, one transaction).
- [ ] T07 ConcurrencyTest: 200 threads, 10-seat section -> exactly 10 CONFIRMED, 0 over capacity.
- [ ] T08 Drop endpoint (release seat, reactivate-row logic from D3).
- [ ] T09 k6 baseline script + verify.sql. Record numbers in docs/RESULTS.md.

## Phase 2: Safety and speed
- [ ] T10 Idempotency (header, store, replay, 422 on body mismatch).
- [ ] T11 Registration time gate (opens_at).
- [ ] T12 Redis seat counters + Lua script + startup/admin rebuild + compensation on DB failure.
- [ ] T13 Waitlist join + position.
- [ ] T14 Auto-promotion on drop (same transaction). Test: drop -> head of waitlist confirmed.
- [ ] T15 Rate limiting per student.
- [ ] T16 Audit log writes.
- [ ] T17 Re-run k6, record improved numbers in docs/RESULTS.md.

## Phase 3: Frontend, proof, deploy
- [ ] T18 Frontend scaffold + login + JWT client.
- [ ] T19 Student: course list, register, my registrations (client generates Idempotency-Key per click).
- [ ] T20 Student: waitlist UI + position.
- [ ] T21 Admin: sections CRUD + stats page.
- [ ] T22 Metrics (Micrometer/actuator) for p95 and registrations/min.
- [ ] T23 Final 2,000+ VU k6 test + verification queries. Results table in README.
- [ ] T24 Deploy + README (architecture diagram, results table, how to run).

## Stretch (only if time remains)
- Request queue (Redis stream + workers), WebSocket live seat counts, email/notification on promotion.
