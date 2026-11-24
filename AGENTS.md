# SeatRush: course registration under load
Stack: Spring Boot 3 (Java 17+), MySQL 8, Redis 7, React (Vite), k6.
Goal: no double allocation, idempotent writes, waitlist auto-promotion, measured under 2,000+ concurrent users.

## Rule 1: Context is a budget
- Read docs/PROGRESS.md first. Never scan the whole repo.
- Open only files named in the task or listed in docs/ARCHITECTURE.md as relevant.
- Don't re-read files already read in this conversation.
- Minimal edits only. No full-file rewrites, no unrequested refactors or features.
- Keep replies short: summary of changes only, no tutorials.

## Rule 2: Load on demand
- Detail lives in /docs. Read a doc only when the task needs it:
  - docs/ARCHITECTURE.md : package map, flows, Redis keys
  - docs/SCHEMA.md       : tables and constraints
  - docs/API.md          : endpoints and error codes
  - docs/DECISIONS.md    : settled choices (append only, do not re-debate)
  - docs/TASKS.md        : ordered task list
- Never load: target/, node_modules/, dist/, logs, loadtest/results/.

## Rule 3: Right-size the work
- Boilerplate (DTOs, CRUD, forms, styling): do it quickly and simply.
- Concurrency, locking, idempotency, Redis Lua: state the invariant first
  (e.g. "seats_taken never exceeds capacity"), then implement.

## Rule 4: Precision
- Do exactly the task stated. If ambiguous, ask ONE question, then proceed.
- Done = the named test passes. Never modify a test to make it pass.
- Don't touch files outside the task's file list.

## Rule 5: Clean context
- One task per session. When finished: update docs/PROGRESS.md (under 15 lines), then stop.
- If the same error appears twice, STOP. Report: error, what you tried, hypothesis. No third blind retry.
- Don't guess config values (ports, credentials, versions). Ask or read docker-compose.yml.

## Non-negotiable invariants
1. MySQL is the source of truth. Redis is a fast gate only.
2. seats_taken <= capacity, always. Enforced in SQL, not just in Java.
3. One registration row per (student, section). Enforced by UNIQUE constraint.
4. Every write endpoint requires an Idempotency-Key header.
5. All booking writes go through BookingService only.

## Conventions
- Layers: controller / service / repository / dto / config
- Package root: com.seatrush
- Schema changes only via new Flyway migrations (never edit an applied one).
- Tests: JUnit 5 + Testcontainers (MySQL, Redis) for integration tests.
