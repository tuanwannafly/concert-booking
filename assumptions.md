# Assumptions & Scope

## Booking States

```
                        ┌──────────┐
                        │ PENDING  │◄─── Created on POST /bookings
                        └────┬─────┘
                             │ POST /{id}/pay (mock)
                             ▼
                    ┌─────────────────┐
                    │ WAITING_PAYMENT │
                    └────────┬────────┘
                             │ payment confirmed
                             ▼
                          ┌──────┐
                          │ PAID │
                          └──┬───┘
                             │ event passes / ops marks complete
                             ▼
                       ┌───────────┐
                       │ COMPLETED │
                       └───────────┘

  PENDING          ──► CANCELLED   (user cancels or ops override)
  WAITING_PAYMENT  ──► CANCELLED   (user cancels or ops override)
  WAITING_PAYMENT  ──► EXPIRED     (auto-scheduler, after 15 min TTL)
```

Transitions not listed above are invalid and will return `BOOKING_INVALID_STATUS`.

---

## Scope In

- Full booking flow: create → pay (mock) → complete, with cancel at any pre-paid stage
- Redis-first inventory management with atomic `DECRBY` and DB optimistic-lock fallback
- Idempotency for booking creation via `X-Idempotency-Key` header (Redis cache + DB unique index)
- JWT authentication with role-based access control (3 roles: `CUSTOMER`, `OPERATOR`, `ADMIN`)
- Voucher validation and application (one voucher per user per booking, PERCENT or FIXED discount)
- Booking expiry scheduler: auto-expires `WAITING_PAYMENT` bookings after 15 minutes
- Suspicious booking detection: flags users with >3 bookings within a 5-minute window
- Ops dashboard: filter/list bookings by concert, status, suspicious flag; manual status override
- Concert lifecycle management for Ops: create, update, publish, add ticket categories
- Flyway-managed DB schema with seed data (users, concerts, categories, vouchers)
- Dockerised deployment: MySQL 8 + Redis 7 + Spring Boot app via Docker Compose
- Swagger/OpenAPI documentation at `/swagger-ui.html`
- 22 REST endpoints (2 public auth + 3 public concert + 5 customer booking + 1 customer voucher + 4 ops concert + 5 ops booking + 2 admin voucher)

---

## Scope Out

| Feature | Reason Not Implemented |
|---|---|
| Real payment gateway (Stripe, VNPay) | Mock `/pay` endpoint sufficient for evaluating booking flow correctness |
| Email / SMS notifications | Requires external service integration; out of scope for 48h timeline |
| Voucher update / delete | Immutable after creation by design — editing live vouchers creates audit risk |
| Seat selection | Only ticket category (VIP / Standard) supported; per-seat mapping adds UI complexity not required |
| User profile update | Not in the specified requirements |
| Rate limiting (API-level) | Mentioned as future work; Redis-based rate limiter would be the approach |
| Analytics UI / reporting dashboard | Data is accessible via Ops API; a frontend dashboard is out of scope |
| Multi-device session management / token revocation | Single JWT secret; token blacklisting not implemented |
| Microservices / event-driven architecture | Deliberate choice — see `system-design.md` for rationale |

---

## Assumptions Made

**Voucher usage**
- One voucher per user per booking. A user cannot apply the same voucher code twice, even across different bookings (enforced by `UNIQUE(voucher_id, user_id)` in `voucher_usages`).
- Vouchers are validated at booking creation time; pre-validation via `POST /vouchers/validate` is provided as a dry-run preview only and does not reserve the voucher.

**Ticket quantity**
- A single booking can contain multiple ticket categories (e.g. 2× VIP + 2× Standard).
- Each category has a configurable `max_per_booking` limit (default: 4). This is enforced per line item, not across the whole booking.

**Payment**
- `POST /bookings/{id}/pay` is a mock endpoint that always succeeds. It transitions status from `PENDING` → `WAITING_PAYMENT` (simulating initiating payment) and a follow-up confirmation would transition to `PAID`. In the current implementation a single call moves the booking to `WAITING_PAYMENT`; a real gateway would post a webhook to confirm.

**Suspicious booking detection**
- A booking is flagged `is_suspicious = true` if the same user has created more than 3 bookings in the last 5 minutes. This is a non-blocking, best-effort flag — it does not cancel the booking. Ops staff review flagged bookings via `GET /ops/bookings/suspicious`.

**Redis availability**
- Redis is treated as an accelerator, not a hard dependency. If Redis is unavailable:
  - Inventory falls back to `@Version` optimistic locking on `ticket_categories.version`.
  - Idempotency falls back to the `UNIQUE` DB index on `bookings.idempotency_key` — a `DataIntegrityViolationException` is caught and surfaced as `BOOKING_ALREADY_EXISTS`.
  - The system degrades gracefully; bookings still process, but throughput is reduced.

**Roles and permissions**
- `CUSTOMER`: browse concerts, create/cancel own bookings, validate vouchers.
- `OPERATOR`: all Customer permissions + manage concerts + view/filter all bookings + override booking status.
- `ADMIN`: all Operator permissions + create vouchers + list all vouchers.

**Concert publish triggers inventory pre-load**
- When an Operator publishes a concert (`POST /ops/concerts/{id}/publish`), available inventory for all ticket categories is loaded into Redis so that all subsequent inventory checks are served from memory.

---

## API Authentication

### Public endpoints (no token required)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Register new customer account |
| `POST` | `/api/v1/auth/login` | Obtain JWT token |
| `GET` | `/api/v1/concerts` | List published concerts |
| `GET` | `/api/v1/concerts/{id}` | Concert detail |
| `GET` | `/api/v1/concerts/{id}/categories` | Available ticket categories |

### Authenticated endpoints

All other endpoints require `Authorization: Bearer <token>`.

| Role | Accessible Paths |
|---|---|
| `CUSTOMER` | `POST /bookings`, `GET /bookings`, `GET /bookings/{id}`, `POST /bookings/{id}/pay`, `DELETE /bookings/{id}`, `POST /vouchers/validate` |
| `OPERATOR` | All Customer endpoints + `/ops/concerts/**` + `/ops/bookings/**` |
| `ADMIN` | All Operator endpoints + `/ops/vouchers/**` |

Tokens are signed HS256 JWTs and expire after 24 hours (configurable via `app.jwt.expiration-ms`).

---

## Limitations

- **No token revocation**: Logging out on the client side discards the token locally, but the JWT remains valid until expiry. A Redis-backed blocklist would be needed for true revocation.
- **Scheduler accuracy**: The expiry scheduler runs on a fixed schedule (every minute by default). Bookings may expire up to 1 minute late under load.
- **Voucher race window**: The voucher lock uses `UNIQUE(voucher_id, user_id)` at the DB layer. Under extreme concurrency, two concurrent requests for the same user+voucher may both pass the application-level check before the constraint fires; one will receive a `409 CONFLICT`. This is intentional — correctness is preserved, the UX is a retryable error.
- **No horizontal scaling without sticky sessions**: The current idempotency cache and inventory counters are stored in a single Redis instance. Adding app replicas behind a load balancer is safe as long as they share the same Redis. A Redis Cluster or Sentinel setup would be required for Redis HA.
- **Inventory pre-load is manual**: If the Redis instance is restarted, inventory counters must be re-seeded by re-publishing the concert or via a manual admin operation (not yet implemented).
- **Optimistic lock retries are not automatic**: If `ObjectOptimisticLockingFailureException` is raised (concurrent DB write), the request fails with `409`. The client is expected to retry.
