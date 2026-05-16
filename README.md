# Concert Ticket Booking — GEEK Up Technical Assessment

## Tech Stack
| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2.5, Java 17 |
| Security | Spring Security 6 + JWT (jjwt 0.12.5) |
| Database | MySQL 8.0 + JPA/Hibernate |
| Cache/Locking | Redis 7.2 |
| Migration | Flyway |
| API Docs | Swagger/OpenAPI 3 (springdoc) |
| Container | Docker + Docker Compose |

---

## Yêu Cầu
- Docker & Docker Compose (v2+)
- Java 17+
- Maven 3.9+ (nếu chạy không qua Docker)

---

## Cách Chạy Local

### 1. Clone và setup env

```bash
git clone <repo-url>
cd concert-booking
cp .env.example .env
# Có thể giữ nguyên giá trị default trong .env
```

### 2. Start MySQL + Redis (phương pháp khuyến nghị)

```bash
# Chỉ start DB + Redis (app chạy trực tiếp bằng Maven)
docker compose up -d mysql redis

# Kiểm tra containers đã ready
docker compose ps
```

### 3. Chạy Spring Boot app

```bash
mvn spring-boot:run
```

App sẽ tự động:
- Chờ MySQL sẵn sàng
- Chạy Flyway migration (tạo schema + seed data)
- Start trên port **8080**

### 4. Truy cập Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Chạy Toàn Bộ Bằng Docker (Full Stack)

```bash
docker compose --profile full up -d --build
```

---

## Stop Services

```bash
docker compose down           # stop containers
docker compose down -v        # stop + xóa volumes (reset data)
```

---

## Accounts Mặc Định (Seed Data)

| Role | Email | Password |
|---|---|---|
| Admin | admin@geekup.vn | password123 |
| Operator | operator1@geekup.vn | password123 |
| Customer | customer1@test.com | password123 |
| Customer | customer2@test.com | password123 |
| Customer | customer3@test.com | password123 |

---

## API Endpoints Overview

### Public
```
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/concerts
GET  /api/v1/concerts/{id}
```

### Customer (yêu cầu CUSTOMER JWT)
```
GET    /api/v1/concerts/{id}/categories
POST   /api/v1/bookings
GET    /api/v1/bookings
GET    /api/v1/bookings/{id}
POST   /api/v1/bookings/{id}/pay
DELETE /api/v1/bookings/{id}
POST   /api/v1/vouchers/validate
```

### Operations (yêu cầu OPERATOR/ADMIN JWT)
```
POST /api/v1/ops/concerts
PUT  /api/v1/ops/concerts/{id}
POST /api/v1/ops/concerts/{id}/publish
POST /api/v1/ops/concerts/{id}/categories
GET  /api/v1/ops/bookings
PUT  /api/v1/ops/bookings/{id}/status
GET  /api/v1/ops/bookings/suspicious
GET  /api/v1/ops/vouchers
POST /api/v1/ops/vouchers
```

---

## Idempotency

Khi tạo booking, client **phải** gửi header:
```
X-Idempotency-Key: <UUID v4>
```

Nếu cùng key gửi lại (retry), server trả về kết quả của lần đầu tiên mà không tạo duplicate.

---

## Unit Tests

```bash
mvn test

# Chỉ chạy test cho BookingService
mvn test -Dtest=BookingServiceTest
```

---

## Cấu Trúc Project

```
src/main/java/com/geekup/concertbooking/
├── ConcertBookingApplication.java
├── config/            # Redis, Security, Swagger, AppProperties
├── entity/            # JPA entities
├── shared/
│   ├── enums/         # UserRole, ConcertStatus, BookingStatus, DiscountType
│   ├── exception/     # AppException, ErrorCode, GlobalExceptionHandler
│   └── response/      # ApiResponse<T>
└── module/
    ├── auth/          # AuthController, AuthService, JwtUtil, JwtFilter
    ├── concert/       # ConcertController, ConcertService, ConcertRepository
    ├── booking/       # BookingController, BookingService, BookingRepository
    ├── voucher/       # VoucherController, VoucherService, VoucherRepository
    └── ops/           # OpsController

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__create_users.sql
    ├── V2__create_concerts.sql
    ├── V3__create_ticket_categories.sql
    ├── V4__create_vouchers.sql
    ├── V5__create_bookings.sql
    └── V6__seed_data.sql
```
