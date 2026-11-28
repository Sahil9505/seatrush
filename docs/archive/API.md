# API (base /api). JWT in Authorization: Bearer <token> unless noted.

## Auth
POST /auth/register   {rollNo, name, email, password}        -> 201
POST /auth/login      {email, password}                      -> 200 {token, role}

## Catalog (any logged-in user)
GET  /courses                      -> courses with sections (seatsLeft, opensAt)
GET  /sections/{id}                -> one section with live seatsLeft

## Registration (STUDENT) - all writes need header Idempotency-Key: <uuid>
POST   /registrations   {sectionId}  -> 201 CONFIRMED | 409 SECTION_FULL | 409 ALREADY_REGISTERED
                                        | 403 NOT_OPEN_YET | 429 RATE_LIMITED
DELETE /registrations/{sectionId}    -> 200 (drops, auto-promotes waitlist)
GET    /registrations/me             -> my registrations

## Waitlist (STUDENT)
POST   /waitlist        {sectionId}  -> 201 {position} | 409 SEATS_AVAILABLE (register instead)
DELETE /waitlist/{sectionId}         -> 200
GET    /waitlist/me                  -> my entries with current position

## Admin (ADMIN)
POST /admin/courses ; POST /admin/sections ; PUT /admin/sections/{id}
GET  /admin/sections/{id}/registrations
GET  /admin/stats                    -> fill rate, registrations/min, waitlist sizes
POST /admin/redis/rebuild            -> rebuild seat counters from MySQL

## Error format
{ "code": "SECTION_FULL", "message": "...", "timestamp": "..." }
Codes: SECTION_FULL, ALREADY_REGISTERED, NOT_OPEN_YET, RATE_LIMITED, IDEMPOTENCY_CONFLICT (422),
       SEATS_AVAILABLE, NOT_FOUND, VALIDATION_ERROR
