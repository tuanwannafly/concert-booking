# 🎵 Concert Ticket Booking Platform

> A production-ready REST API for high-concurrency concert ticket booking, built for the GEEK Up Technical Assessment. Handles flash-sale inventory with Redis-first atomic locking, idempotent booking creation, role-based access control, and a full ops dashboard — all in a single Dockerised Spring Boot service.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    Client (HTTP/REST)                    │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│                   Spring Boot App :8080                  │
│                                                          │
│  JWT Auth Filter → Controllers → Service Layer           │
│                        ↓              ↓                  │
│               JPA Repositories    RedisTemplate          │
│               (MySQL source of    (inventory counters,   │
│                truth + @Version)   idempotency cache)    │
└───────────────┬──────────────────────┬───────────────────┘
                │                      │
       ┌────────▼───────┐    ┌─────────▼──────┐
       │   MySQL 8 :3307 │    │  Redis 7 :6379 │
       │  (ACID, Flyway) │    │  (DECRBY, TTL) │
       └─────────────────┘    └────────────────┘
```

| Layer | Responsibility |
|---|---|
| Controller | Parse HTTP, extract JWT principal, delegate to Service |
| Service | Business logic, Redis ops, `@Transactional` boundaries |
| Repository | JPA queries; zero business logic |
| Redis | Atomic inventory counters, idempotency response cache |
| GlobalExceptionHandler | Maps `AppException` → consistent `ApiResponse.error()` |

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Framework | Spring Boot 3.2.5 · Java 17 | Core application runtime |
| Security | Spring Security 6 + jjwt 0.12.5 | JWT auth, RBAC (`CUSTOMER / OPERATOR / ADMIN`) |
| Database | MySQL 8.0 + JPA/Hibernate | Persistent store, optimistic locking (`@Version`) |
| Cache | Redis 7.2 | Inventory counters, idempotency cache |
| Migration | Flyway | Versioned schema + seed data on startup |
| API Docs | springdoc-openapi 3 | Swagger UI auto-generated from annotations |
| Container | Docker + Docker Compose v2 | One-command reproducible environment |

---

## ⚡ Quick Start (Docker — recommended)

```bash
git clone <repo-url> concert-booking && cd concert-booking
```

```bash
docker compose --profile full up --build -d
```

```bash
# Wait ~30s for MySQL health check, then open:
open http://localhost:8080/swagger-ui.html
```

That's it. Flyway runs migrations and seeds all test data automatically.

---

## Local Development

**Prerequisites:** Java 17+, Maven 3.8+, Docker

```bash
# 1. Start only the backing services
docker compose up -d mysql redis

# 2. Run the app with Maven (hot-reload friendly)
mvn spring-boot:run
```

App starts on `http://localhost:8080`. Flyway migrations run on first launch.

---

## API Documentation

Interactive docs: **http://localhost:8080/swagger-ui.html**

| Group | Base Path | Auth |
|---|---|---|
| Auth | `POST /api/v1/auth/register` · `POST /api/v1/auth/login` | Public |
| Concerts (browse) | `GET /api/v1/concerts/**` | Public |
| Bookings | `POST /api/v1/bookings` · `GET` · `DELETE` · `POST /{id}/pay` | `CUSTOMER` |
| Vouchers | `POST /api/v1/vouchers/validate` | `CUSTOMER` |
| Ops — Concerts | `POST /api/v1/ops/concerts/**` | `OPERATOR` |
| Ops — Bookings | `GET /api/v1/ops/bookings/**` · `PUT /{id}/status` | `OPERATOR` |
| Admin — Vouchers | `GET/POST /api/v1/ops/vouchers` | `ADMIN` |

All authenticated endpoints require `Authorization: Bearer <token>` obtained from `/auth/login`.

Booking creation requires an additional header: `X-Idempotency-Key: <UUIDv4>`.

---

## Test Accounts

All accounts use password `password123`.

| Email | Role | Notes |
|---|---|---|
| `admin@geekup.vn` | `ADMIN` | Full access including voucher management |
| `operator1@geekup.vn` | `OPERATOR` | Concert & booking ops dashboard |
| `customer1@test.com` | `CUSTOMER` | Standard customer |
| `customer2@test.com` | `CUSTOMER` | Standard customer |
| `customer3@test.com` | `CUSTOMER` | Standard customer |

**Seeded voucher codes:**

| Code | Type | Value | Min Order |
|---|---|---|---|
| `FLASHSALE10` | Percent | 10% off | 500,000 ₫ |
| `VIP200K` | Fixed | −200,000 ₫ | 1,500,000 ₫ |

---

## Key Features & Design Highlights

- **Redis-first inventory (DECRBY atomic)** — Concurrent booking requests are gated by a Redis `DECRBY` before touching MySQL. Only requests that decrement to `≥ 0` proceed to the DB write; others receive `TICKET_SOLD_OUT` immediately. A `@Version` optimistic lock on `ticket_categories` acts as the DB-layer safety net. If Redis is unavailable, the system degrades gracefully to DB-only locking.

- **Idempotent booking creation** — Every `POST /bookings` requires `X-Idempotency-Key`. The first successful response is cached in Redis (TTL 24h) and replayed verbatim on retries — safe for client-side retry loops without risk of duplicate bookings. The DB `UNIQUE` index on `idempotency_key` provides a second layer of protection.

- **Booking expiry scheduler** — A background job runs every 60 seconds to transition `WAITING_PAYMENT` bookings that exceed the 15-minute TTL to `EXPIRED`, releasing inventory back to both Redis and MySQL.

- **Suspicious booking detection** — Non-blocking: if a user creates more than 3 bookings within any 5-minute window, all bookings in that window are flagged `is_suspicious = true`. Ops staff can review via `GET /api/v1/ops/bookings/suspicious` without impacting the customer experience.

---

## Postman Collection

1. Import via **File → Import → Link** using the Swagger JSON:
   ```
   http://localhost:8080/v3/api-docs
   ```
2. Create an environment with variable `baseUrl = http://localhost:8080`.
3. Authenticate first: `POST {{baseUrl}}/api/v1/auth/login` → copy `data.token`.
4. Set collection-level **Auth → Bearer Token** to `{{token}}`, then all protected requests inherit it automatically.

---

## Running Tests

```bash
# All unit tests
mvn test

# Single service class
mvn test -Dtest=BookingServiceTest

# Single method
mvn test -Dtest=BookingServiceTest#createBooking_soldOut_throwsAppException

# With JaCoCo coverage report → target/site/jacoco/index.html
mvn test jacoco:report
```

Tests use `MockitoExtension` — no Spring context is loaded, so they run in seconds.

---

## Known Limitations

| Limitation | Detail |
|---|---|
| No token revocation | JWTs are valid until the 24h expiry; no Redis blocklist implemented |
| Redis restart loses inventory | Counters must be re-seeded by re-publishing the concert |
| Scheduler ±60s accuracy | Expiry may fire up to 1 minute late under load |
| No horizontal Redis HA | Multiple app replicas are safe as long as they share one Redis instance |
| Real payment gateway | `/pay` is a mock endpoint; no Stripe/VNPay integration |
| No email/SMS notifications | Out of scope for 48h delivery timeline |

---

## Project Structure

```
src/main/java/com/geekup/concertbooking/
├── config/          # Security, Redis, Swagger, AppProperties
├── entity/          # JPA entities (Booking, Concert, Voucher …)
├── shared/          # Enums, AppException, ErrorCode, ApiResponse<T>
└── module/
    ├── auth/        # Register, login, JWT filter
    ├── concert/     # Public browse + Ops CRUD + publish
    ├── booking/     # Create, pay, cancel, expiry scheduler
    ├── voucher/     # Validate + Admin CRUD
    └── ops/         # Ops booking filter, status override

src/main/resources/db/migration/
    V1__create_users.sql … V6__seed_data.sql
```
