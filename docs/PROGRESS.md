# Progress (AI: read this first, update it last)
## Done
- [x] Course v1 (T01-T08) & Pivot docs (P01).
- [x] P02-P03 Schema squash & P04 Seed (dev profile).
- [x] B01 Pure bitmask availability functions (SPEC 4).
- [x] B02 Naive booking baseline & race concurrency test (SPEC 6).

## Next
1. B03 DB-correct booking (SPEC 6, 7, 8)

## Known issues: none
Same user, two simultaneous requests with different keys can both pass the overlap check. Idempotency (B04) covers real double-clicks.

## Last session summary
- B02 done: NaiveBookingConcurrencyTest passed. Unconstrained table showed 40 double allocations; real unique constraint prevented 93 collisions with 0 double allocations.
