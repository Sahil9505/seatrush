# Results

All numbers are from my own machine. Single runs unless stated; ranges are added when repeated.

## Setup
- Machine: MacBook Air, <CPU / RAM>
- MySQL 8.0 and Redis 7 in Docker (MySQL max_connections 500)
- Hikari pool size: <value from application-mysqltest.yml>
- Section 1: JUnit threads inside one JVM against MySQL (no HTTP).
- Section 2: k6 on the same machine as the app and the databases, so numbers are relative, not absolute.

## 1. Correctness under concurrency (JUnit, MySQL)
Fixture: 100 concurrent users, 1 journey, 6 stops (5 segments), 10 seats, every user requests the whole route.
Ideal result: exactly 10 bookings and 0 double-allocated seat-segments (50 seat-segments in total).

| Version | Run | Successes | Sold out | Rejected by DB constraint | Double-allocated seat-segments |
|---|---|---|---|---|---|
| B02 naive, no unique constraint | 1 | 100 | 0 | n/a | 45 of 50 |
| B02 naive, with unique constraint | 1 | 6 | 0 | 94 | 0 |
| B03 DB-correct (retry + constraint) |  |  |  |  |  |
| B05 Redis-gated |  |  |  |  |  |

Notes:
- Naive without the constraint sold the same 10 seats to up to 100 users.
- Naive with the constraint stays correct but wastes seats, because it never retries.

## 2. Load test (k6, HTTP), filled at K1 and K2
| Version | Virtual users | Duration | Req/s | p50 ms | p95 ms | p99 ms | Overlapping allocations | 5xx |
|---|---|---|---|---|---|---|---|---|
| DB-correct (B03) |  |  |  |  |  |  | 0 |  |
| + Idempotency (B04) |  |  |  |  |  |  | 0 |  |
| + Redis gate (B05) |  |  |  |  |  |  | 0 |  |
| Final (K2) |  |  |  |  |  |  | 0 |  |