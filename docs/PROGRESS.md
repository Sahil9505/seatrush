# Progress (AI: read this first, update it last)
## Done
- [x] T01-T04 Setup, entities, auth, dev seeder
- [x] T05 Catalog endpoints (GET /api/courses, /api/sections/{id})
- [x] T06 BookingService.register() DB-only
- [x] T07 BookingConcurrencyTest (200 threads, 10-seat section, lock ordering fix)
## In progress
- (none)
## Next (in order)
1. T08 Drop endpoint (release seat, reactivate-row logic from D3)
## Known issues
- (none)
## Last session summary
- T07 done: Concurrency tests implemented & green; fixed S->X lock upgrade deadlock by running takeSeat before registration insert.
