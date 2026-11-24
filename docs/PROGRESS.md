# Progress (AI: read this first, update it last)
## Done
- [x] T01 Scaffold Spring Boot 3 backend, Flyway V1 applied, health UP
- [x] T02 Entities + repositories for all tables (context-load validated)
- [x] T03 Auth: JWT (jjwt 0.12.6), BCrypt register/login, SecurityConfig, GlobalExceptionHandler.
- [x] T04 Dev seed runner (CommandLineRunner): 1 admin, 5k students, 20 courses, 50 sections. SeedTest green.
## In progress
- (none)
## Next (in order)
1. T05 Catalog endpoints (GET /courses, /sections/{id})
## Known issues
- (none)
## Last session summary
- T04 done: DataSeeder under "dev" profile batch-inserts seed data reusing 1 BCrypt hash; SeedTest verifies counts & idempotency on MySQL.
