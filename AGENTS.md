# SeatRush: train seat booking engine
Stack: Spring Boot 3 (Java 17+), MySQL 8, Redis 7 (Lua), React (Vite), k6.
Goal: zero double allocation of a seat for overlapping route segments, idempotent requests,
payment holds, fit-aware waitlist, measured under 2,000+ concurrent users.
Source of truth: docs/SPEC.md. Do not change anything marked [FIXED] without asking.

## Rule 1: Context is a budget
- Read docs/PROGRESS.md first. Never scan the whole repo.
- Open only files named in the task. Read only the SPEC.md sections the task needs.
- Don't re-read files already read in this conversation.
- Minimal edits only. No full-file rewrites, no unrequested refactors or features.
- Keep replies short: summary of changes only, no tutorials.

## Rule 2: Load on demand
- docs/SPEC.md (sections by number), docs/TASKS.md, docs/DECISIONS.md. Old course-version docs
  are in docs/archive/ : do not read them unless the task says so.
- Never load: target/, node_modules/, dist/, logs, loadtest/results/.

## Rule 3: Right-size the work
- Boilerplate (DTOs, CRUD, forms, styling): do it quickly and simply.
- Bitmask logic, Lua scripts, transactions, idempotency, sweeper, waitlist promotion:
  state the invariant first, then implement.

## Rule 4: Precision
- Do exactly the task stated. If ambiguous, ask ONE question, then proceed.
- Never edit files before the user replies OK to your plan, even if the task prompt seems to authorize it.
- Done = the named test passes. Never modify a test to make it pass.
- Don't touch files outside the task's file list.

## Rule 5: Clean context
- One task per session. When finished: update docs/PROGRESS.md (under 15 lines), then stop.
- If the same error appears twice, STOP. Report: error, what you tried, hypothesis. No third blind retry.
- Don't probe Docker, ports or the environment. The user runs tests and pastes results back.
  Write code, give the exact command, and stop.
- Don't guess config values. Read docker-compose.yml.

## Non-negotiable invariants
1. MySQL is the source of truth. Redis is a fast gate only.
2. seat_allocations UNIQUE(journey_id, seat_id, segment_index): a seat-segment is never stored twice.
3. A booking from stop a to stop b occupies segments a .. b-1 (b-a rows, one transaction).
4. Every booking and payment write requires an Idempotency-Key header.
5. All allocation writes go through the booking/allocation service only.
6. Expiry decisions use database or Redis time, never the client clock. No Redis key-expiry notifications.
7. Confirming a hold is one conditional UPDATE ... WHERE status='HELD' AND hold_expires_at > NOW().

## Conventions
- Package root: com.seatrush. Layers: controller / service / repository / dto / config.
- Schema changes via Flyway only (never edit an applied migration after the pivot squash).
- Lock order (D10): if a transaction both inserts rows that reference a parent row and updates that
  parent row, take the exclusive lock first, then insert. Otherwise S->X upgrade deadlocks.
- Tests: functional tests on H2 (profile "test"); concurrency and Redis tests on real MySQL/Redis from
  docker-compose (profile "mysqltest", schema seatrush_test, test data prefix "cc-"). H2 results are
  never used as concurrency evidence.
- Entity column types must match the migration exactly (ddl-auto=validate). For CHAR(n) columns use @JdbcTypeCode(SqlTypes.CHAR)