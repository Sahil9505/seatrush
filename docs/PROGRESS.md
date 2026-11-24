# Progress (AI: read this first, update it last)
## Done
- [x] T01 Scaffold Spring Boot 3 backend, Flyway V1 applied, health UP
- [x] T02 Entities + repositories for all tables (context-load validated)
- [x] T03 Auth: JWT (jjwt 0.12.6), BCrypt register/login, SecurityConfig (stateless, CORS, roles), GlobalExceptionHandler. AuthIntegrationTest 6/6 green.
## In progress
- (none)
## Next (in order)
1. T04 Catalog endpoints (GET /api/courses, GET /api/sections/{id})
## Known issues
- (none)
## Last session summary
- T03 done: DTOs, AppException+GlobalExceptionHandler, JwtService, JwtAuthFilter, StudentUserDetailsService, AuthService, AuthController, SecurityConfig rewritten. Test profile uses H2 + schema.sql + httpclient5. AuthIntegrationTest 6/6 pass.
