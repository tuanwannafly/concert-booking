# Coding Guidelines

## Project Structure

The project uses a **package-by-feature** layout. Each business domain is a self-contained module under `module/`. Shared infrastructure lives in `shared/` and `config/`.

```
com.geekup.concertbooking/
│
├── config/
│   ├── AppProperties.java          # Typed config binding (@ConfigurationProperties)
│   ├── RedisConfig.java            # RedisTemplate bean
│   ├── SecurityConfig.java         # JWT filter chain, role mappings
│   └── SwaggerConfig.java          # OpenAPI metadata
│
├── entity/                         # JPA entities (shared across modules)
│   ├── Booking.java
│   ├── BookingItem.java
│   ├── Concert.java
│   ├── TicketCategory.java
│   ├── User.java
│   ├── Voucher.java
│   └── VoucherUsage.java
│
├── module/
│   ├── auth/
│   │   ├── AuthController.java
│   │   ├── AuthService.java
│   │   ├── JwtAuthFilter.java
│   │   ├── JwtUtil.java
│   │   ├── UserDetailsServiceImpl.java
│   │   ├── UserRepository.java
│   │   └── dto/
│   │       ├── AuthResponse.java
│   │       ├── LoginRequest.java
│   │       └── RegisterRequest.java
│   │
│   ├── booking/
│   │   ├── BookingController.java
│   │   ├── BookingExpiryScheduler.java
│   │   ├── BookingItemRepository.java
│   │   ├── BookingRepository.java
│   │   ├── BookingService.java
│   │   ├── UserIdResolver.java
│   │   └── dto/
│   │       ├── BookingItemRequest.java
│   │       ├── BookingItemResponse.java
│   │       ├── BookingResponse.java
│   │       ├── CreateBookingRequest.java
│   │       └── PaymentResponse.java
│   │
│   ├── concert/
│   │   ├── ConcertController.java      # Public endpoints
│   │   ├── OpsConcertController.java   # Ops/Admin endpoints
│   │   ├── ConcertRepository.java
│   │   ├── ConcertService.java
│   │   ├── TicketCategoryRepository.java
│   │   └── dto/ …
│   │
│   ├── ops/
│   │   ├── OpsBookingController.java
│   │   ├── OpsBookingService.java
│   │   ├── BookingSpecification.java
│   │   └── dto/ …
│   │
│   └── voucher/
│       ├── VoucherController.java      # Customer validate endpoint
│       ├── OpsVoucherController.java   # Admin CRUD
│       ├── VoucherRepository.java
│       ├── VoucherService.java
│       ├── VoucherUsageRepository.java
│       └── dto/ …
│
└── shared/
    ├── enums/
    │   ├── BookingStatus.java
    │   ├── ConcertStatus.java
    │   ├── DiscountType.java
    │   └── UserRole.java
    ├── exception/
    │   ├── AppException.java
    │   ├── ErrorCode.java
    │   └── GlobalExceptionHandler.java
    └── response/
        └── ApiResponse.java
```

---

## How to Add a New API

Follow these steps in order. Example: adding `GET /api/v1/concerts/{id}/stats`.

### Step 1 — Create DTOs in `module/{feature}/dto/`

```java
// module/concert/dto/ConcertStatsResponse.java
public record ConcertStatsResponse(
    Long   concertId,
    long   totalBookings,
    long   totalTicketsSold,
    BigDecimal totalRevenue
) {}
```

Use Java **records** for immutable response DTOs. Use records or `@Valid`-annotated classes for request DTOs.

### Step 2 — Add Repository method

```java
// module/concert/ConcertRepository.java (or a new ConcertStatsRepository)
public interface ConcertRepository extends JpaRepository<Concert, Long> {

    // Custom JPQL or native query
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.concert.id = :concertId AND b.status <> 'CANCELLED'
        """)
    long countActiveBookingsByConcertId(@Param("concertId") Long concertId);
}
```

### Step 3 — Implement Service method

```java
// module/concert/ConcertService.java
@Transactional(readOnly = true)
public ConcertStatsResponse getConcertStats(Long concertId) {
    Concert concert = concertRepository.findById(concertId)
        .orElseThrow(() -> new AppException(ErrorCode.CONCERT_NOT_FOUND));

    long totalBookings = bookingRepository.countActiveBookingsByConcertId(concertId);
    // … compute other fields

    return new ConcertStatsResponse(concertId, totalBookings, …);
}
```

Business logic lives **only** in the Service layer. Controllers and Repositories contain no business logic.

### Step 4 — Add Controller endpoint

```java
// module/concert/OpsConcertController.java
@Operation(summary = "Get concert booking statistics")
@GetMapping("/{id}/stats")
public ResponseEntity<ApiResponse<ConcertStatsResponse>> getConcertStats(
        @PathVariable Long id) {
    ConcertStatsResponse stats = concertService.getConcertStats(id);
    return ResponseEntity.ok(ApiResponse.success(stats));
}
```

### Step 5 — Add Swagger `@Operation` annotation

Always include `summary` (required) and `description` (for non-obvious endpoints):

```java
@Operation(
    summary = "Get concert booking statistics",
    description = "Returns aggregate booking and revenue figures for a concert. Operator/Admin only."
)
```

### Step 6 — Write a test case

```java
// module/concert/ConcertServiceTest.java
@Test
void getConcertStats_validConcert_returnsAggregates() {
    // Arrange
    Long concertId = 1L;
    given(concertRepository.findById(concertId)).willReturn(Optional.of(mockConcert));
    given(bookingRepository.countActiveBookingsByConcertId(concertId)).willReturn(42L);

    // Act
    ConcertStatsResponse result = concertService.getConcertStats(concertId);

    // Assert
    assertThat(result.totalBookings()).isEqualTo(42L);
}

@Test
void getConcertStats_concertNotFound_throwsAppException() {
    given(concertRepository.findById(99L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> concertService.getConcertStats(99L))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).getErrorCode())
        .isEqualTo(ErrorCode.CONCERT_NOT_FOUND);
}
```

---

## Error Handling Convention

### Throwing errors

**Always** use `AppException` — never throw `RuntimeException` or other unchecked exceptions directly:

```java
// ✅ Correct
throw new AppException(ErrorCode.CONCERT_NOT_FOUND);

// ✅ With custom message (overrides the enum default)
throw new AppException(ErrorCode.TICKET_QUANTITY_EXCEEDED,
    "Category 'VIP' allows max 4 tickets per booking");

// ❌ Wrong — bypasses GlobalExceptionHandler mapping
throw new RuntimeException("Concert not found");
```

### Adding a new ErrorCode

1. Open `shared/exception/ErrorCode.java`.
2. Add an entry in the appropriate section:

```java
// Concert
CONCERT_ALREADY_ENDED (HttpStatus.BAD_REQUEST, "Concert has already ended"),
```

3. Use it immediately in the Service:

```java
throw new AppException(ErrorCode.CONCERT_ALREADY_ENDED);
```

`GlobalExceptionHandler` automatically maps any `AppException` to the correct HTTP status and `ApiResponse.error()` body. No additional wiring is needed.

### How GlobalExceptionHandler works

```
AppException(ErrorCode.XXX)
    → GlobalExceptionHandler.handleAppException()
    → ResponseEntity(status=ErrorCode.httpStatus, body=ApiResponse.error(code, message))

MethodArgumentNotValidException (Bean Validation)
    → GlobalExceptionHandler.handleValidationException()
    → ResponseEntity(400, body=ApiResponse.error("VALIDATION_ERROR", fieldErrors))

Exception (unexpected)
    → GlobalExceptionHandler.handleGenericException()
    → ResponseEntity(500, body=ApiResponse.error("INTERNAL_SERVER_ERROR", …))
```

---

## API Response Convention

All endpoints return `ResponseEntity<ApiResponse<T>>`. Never return a raw domain object or `Map`.

```java
// ApiResponse structure
{
  "success": true,
  "data": { … },
  "message": "Created successfully"   // optional, present on 201s
}

// Error structure
{
  "success": false,
  "error": {
    "code": "TICKET_SOLD_OUT",
    "message": "Vé đã hết, không thể đặt thêm"
  }
}
```

**Usage patterns:**

```java
// 200 OK
return ResponseEntity.ok(ApiResponse.success(data));

// 201 Created
return ResponseEntity.status(HttpStatus.CREATED)
    .body(ApiResponse.success(data, "Created successfully"));

// Errors — handled automatically by GlobalExceptionHandler
// Just throw AppException; never construct error responses manually in a Controller.
```

---

## How to Run Tests

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=BookingServiceTest

# Run a single test method
mvn test -Dtest=BookingServiceTest#createBooking_soldOut_throwsAppException

# Run with coverage report (target/site/jacoco/index.html)
mvn test jacoco:report
```

### Test naming convention

Format: `methodName_scenario_expectedResult`

```java
createBooking_validRequest_returnsBookingResponse()
createBooking_idempotencyKeyAlreadyUsed_returnsCachedResponse()
createBooking_soldOut_throwsAppException()
createBooking_invalidVoucherCode_throwsAppException()
cancelBooking_paidStatus_throwsBookingCannotCancel()
```

### Test structure

Tests use **Mockito** (`@ExtendWith(MockitoExtension.class)`) with `@Mock` dependencies injected via `@InjectMocks`. Do not use `@SpringBootTest` for unit tests — it starts the full application context and is slow.

```java
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository       bookingRepository;
    @Mock RedisTemplate<String, String> redisTemplate;
    // … other mocks

    @InjectMocks BookingService bookingService;

    @Test
    void methodName_scenario_expectedResult() {
        // Arrange — set up mocks
        // Act    — call service method
        // Assert — verify result or exception
    }
}
```

---

## Security Convention

### Protecting endpoints by role

```java
// On the Controller class — applies to all methods in the class
@PreAuthorize("hasRole('ADMIN')")
public class OpsVoucherController { … }

// Or on a specific method — for mixed-role controllers
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
@PutMapping("/{id}/status")
public ResponseEntity<…> updateStatus(…) { … }

// Customer (any authenticated user)
@PreAuthorize("isAuthenticated()")
public class BookingController { … }
```

Available role strings: `'CUSTOMER'`, `'OPERATOR'`, `'ADMIN'` — must match the `UserRole` enum values prefixed with `ROLE_` internally by Spring Security.

### Extracting the authenticated user's ID in a Controller

```java
@PostMapping
public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
        @RequestBody @Valid CreateBookingRequest request,
        @RequestHeader("X-Idempotency-Key") String idempotencyKey,
        Authentication authentication) {

    Long userId = UserIdResolver.resolve(authentication);
    BookingResponse response = bookingService.createBooking(request, userId, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
}
```

`UserIdResolver.resolve(authentication)` is a shared utility that extracts the numeric user ID from the JWT principal. Do not cast `authentication.getPrincipal()` directly in Controller methods — always go through `UserIdResolver` to keep extraction centralised.

### Never trust user input for ownership

Always verify that the authenticated user owns the resource before returning or modifying it:

```java
// ✅ Correct
Booking booking = bookingRepository.findById(id)
    .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));
if (!booking.getUser().getId().equals(userId)) {
    throw new AppException(ErrorCode.BOOKING_NOT_OWNED);
}

// ❌ Wrong — leaks other users' booking data
Booking booking = bookingRepository.findById(id)
    .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_FOUND));
return BookingResponse.from(booking);
```
