# SeatRush
Course/section registration under load: no double allocation, idempotent requests, waitlist auto-promotion.

(Fill in after T24: architecture diagram, results table from docs/RESULTS.md, how to run.)

## Run locally
docker compose up -d
cd backend && ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
