# F1 Betting Backend

A Formula 1 betting REST API built with **Spring Boot 3**, **H2 In-Memory Database** (zero setup, plug-and-play), and the live **OpenF1 public API** (featuring client-side rate limiting, bounded LRU caching, resilient mock fallback, and bulletproof transactional concurrency control).

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                           REST Controllers                             │
│   EventController    BetController    OutcomeController  UserController│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                            Service Layer                               │
│      EventService     BettingService    OutcomeService    UserService  │
│                      (SessionLockManager & TransactionTemplate)        │
└─────────────┬───────────────────────────────────────────┬──────────────┘
              │                                           │
┌─────────────▼──────────────────────────┐  ┌─────────────▼──────────────┐
│          F1 Data Adapter Layer         │  │     JPA Repositories       │
│                                        │  │  (H2 In-Memory Database)   │
│  ┌─ OpenF1EventProvider (@Primary)     │  │                            │
│  │  ├─ OpenF1Client (RestTemplate)     │  │  UserRepository           │
│  │  ├─ OpenF1RateLimiter               │  │  BetRepository            │
│  │  │  (Resilience4j Token Bucket &    │  │  EventOutcomeRepository   │
│  │  │   CircuitBreaker with Success/   │  │   (Unique constraint on    │
│  │  │   Failure Tracking, 429 backoff) │  │    session_key)            │
│  │  └─ Bounded LRU & TTL Caching       │  │                            │
│  │     (Auto-eviction, max capacity,   │  │  Web Console:              │
│  │      negative cache for 404s)       │  │   /h2-console              │
│  │                                     │  └────────────────────────────┘
│  └─ MockF1EventProvider (Fallback)     │
│        (Authentic historical sessions &│
│         grid when offline/rate-limited)│
└──────────────────┬─────────────────────┘
                   ↕ HTTP
            api.openf1.org/v1
```

### Key Design Highlights

1. **Provider Abstraction**: All business services depend on the `F1EventProvider` interface rather than directly on OpenF1. Swapping to a commercial data provider or custom feed requires only a new interface implementation with zero changes to services or controllers.
2. **Zero-Setup Embedded Database**: Uses an embedded H2 in-memory database with automatic Hibernate schema initialization (`create-drop`). No external database installation, DBA grants, or connection pool setup is required to run the application or tests.
3. **Decoupled External I/O & Thread Pool Isolation**: External driver validation is performed **before and outside** the database transaction using a dedicated bounded executor pool (`f1ValidationExecutor`), preventing Hikari connection starvation and JVM `ForkJoinPool.commonPool()` exhaustion. External calls are cancelled immediately on timeout.
4. **Race-Free Concurrency Control (`SessionLockManager`)**: Fine-grained per-session locking eliminates TOCTOU races between concurrent `placeBet` and `recordOutcome` calls. `placeBet` re-checks outcome presence inside the locked transaction; `recordOutcome` prevents concurrent duplicate settlements and translates database integrity violations into clean error responses.
5. **Proactive Rate Limiting & Circuit Breaker**: The public OpenF1 API imposes strict limits (3 req/s burst, ~30 req/min). `OpenF1RateLimiter` pairs a Resilience4j rate limiter with a sliding-window circuit breaker tracking both successes and failures, burst throttling (>= 350ms delay), and dynamic 429 backoff honoring `Retry-After` headers.
6. **Bounded LRU Caching & Memory Protection**: Session and driver caches use thread-safe bounded LRU collections with automatic capacity eviction and TTL expiration, neutralizing memory-exhaustion vectors from arbitrary session keys.
7. **Cancelled Event Filtering**: OpenF1 sessions flagged with `is_cancelled: true` (e.g. Imola 2023) are automatically filtered out from active event listings.
8. **Winner Driver Validation**: Declared race winners in `OutcomeService` are verified against the official driver roster for that session before any bets are settled.
9. **Secure Exception Handling**: `GlobalExceptionHandler` masks internal server errors to prevent information disclosure while cleanly handling database constraint conflicts, malformed JSON, and path variable type mismatches.
10. **Observability & Diagnostics**: Spring Boot Actuator (`/actuator/health`) and interactive H2 Web Console (`/h2-console`) for real-time inspection.

---

## Prerequisites

| Tool | Version | Description |
|------|---------|-------------|
| Java | 17+ | OpenJDK or Oracle JDK |
| Maven | 3.8+ | Build and dependency management |
| Database | *None required* | Embedded H2 database runs in-memory |

---

## Database & H2 Web Console

The application uses an embedded **H2 in-memory database**. 

- **Zero configuration**: Tables (`f1_users`, `f1_bets`, `f1_event_outcomes`) and sequences (`user_seq`, `bet_seq`, `outcome_seq`) are automatically created on startup via Hibernate DDL.
- **H2 Web Console**: Enabled by default for easy database inspection and querying.

### Connecting to H2 Console:
1. Start the application (`mvn spring-boot:run`).
2. Open your browser and navigate to: **`http://localhost:8080/h2-console`**
3. Enter the connection settings:
   - **Saved Settings**: Generic H2 (Embedded)
   - **Driver Class**: `org.h2.Driver`
   - **JDBC URL**: `jdbc:h2:mem:f1betting`
   - **User Name**: `sa`
   - **Password**: *(leave blank)*
4. Click **Connect** to view and query tables.

> **Note on Persistence**: Because H2 is configured in-memory (`jdbc:h2:mem:f1betting`), the database cleanly resets whenever the application restarts.

---

## Configuration

The application is configured via `src/main/resources/application.properties`:

```properties
# ============================================================
# H2 In-Memory Database (zero setup — data resets on restart)
# ============================================================
spring.datasource.url=jdbc:h2:mem:f1betting;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# H2 web console — browse to http://localhost:8080/h2-console
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# JPA / Hibernate
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.open-in-view=false

# Jackson
spring.jackson.serialization.write-dates-as-timestamps=false

# Application
server.port=8080
spring.application.name=f1-betting-app

# OpenF1 API (external provider - swap URL here to change provider)
openf1.api.base-url=https://api.openf1.org/v1
openf1.api.connect-timeout-ms=15000
openf1.api.read-timeout-ms=25000

# OpenF1 Client-Side Rate Limiter (Free tier: 3 req/s burst, ~30 req/min sustained)
openf1.rate-limit.requests-per-second=3
openf1.rate-limit.requests-per-minute=25
openf1.rate-limit.cooldown-seconds=60
openf1.rate-limit.failure-cooldown-seconds=20

# OpenF1 In-Memory Caching (TTLs in minutes)
openf1.cache.session-ttl-minutes=15
openf1.cache.driver-ttl-minutes=60
openf1.cache.negative-ttl-minutes=15

# Event listing optimization threshold
events.bulk-listing-threshold=20

# Betting validation timeout in seconds for external driver check
betting.driver-validation-timeout-seconds=8
```

---

## Concurrency, Rate Limiting & Resilience

### Concurrency & Transaction Management
- **`SessionLockManager`**: Provides exclusive, non-blocking lock striping per `sessionKey`. Bet placements and outcome settlements on the same session are serialized, preventing any bet from being accepted while an outcome is being recorded.
- **Transaction Scope**: `placeBet` and `recordOutcome` perform external validation calls completely outside the DB transaction. A `TransactionTemplate` wraps only the actual database mutations (balance debit, bet persistence, winner settlement).
- **Pessimistic Locking**: `userRepository.findByIdForUpdate()` ensures user balances cannot be overdrawn under concurrent requests.

### OpenF1 Rate Limiting & Circuit Breaker (`OpenF1RateLimiter`)
- **Inter-Request Throttling**: Spaces outbound requests by at least ~368ms, keeping burst rates safely under 3 req/s.
- **Sliding Window Cap**: Limits calls to 25 requests/minute over a 60-second rolling window.
- **Circuit Breaker Tracking**: Outbound successes record elapsed time via `recordSuccess()`, while timeouts and network errors trip `recordFailure()`, allowing Resilience4j to compute accurate error rates and recover automatically.
- **HTTP 429 Cooldown**: Parses upstream `Retry-After` headers (defaulting to 60s) and suspends outbound requests during active backoff.

### Bounded In-Memory Caching & Eviction
- **Sessions Cache**: Bounded LRU cache (max 200 entries, 15-minute TTL).
- **Drivers Cache**: Bounded LRU cache (max 500 entries, 60-minute TTL).
- **Memory Protection**: Automatically evicts eldest or expired entries on insertion, neutralizing memory-exhaustion attacks.
- **Negative Caching**: Sessions with no recorded drivers or cancelled telemetry are cached for 15 minutes to prevent hammering OpenF1.

### Mock Fallback (`MockF1EventProvider`)
When OpenF1 rate limits are active, the API is unreachable, or an error occurs, the provider seamlessly serves authentic historical sessions with authentic session keys:
- **Monza 2023 Race**: session key `9157`
- **Monaco 2024 Race**: session key `9523`
- **Silverstone 2024 Race**: session key `9558`
- **Bahrain 2024 Race**: session key `9472`
*(Backward-compatible aliases `9161`, `9520`, and `9540` are also recognized).*

---

## Building, Testing & Running

### 1. Build and Run Tests
```bash
# Clone / navigate to project root
cd f1-betting-app

# Run all unit and integration tests (48 tests)
mvn test
```

### 2. Package Application
```bash
mvn clean package
```

### 3. Run Application
```bash
# Direct run via Maven
mvn spring-boot:run

# Or run the packaged JAR
java -jar target/f1-betting-app-1.0.0.jar
```

The application starts on **`http://localhost:8080`**.

---

## Seeded Users

On startup, `DataInitializer` seeds three default users with **100.00 EUR** starting balance each:

| ID | Username | Starting Balance |
|----|----------|------------------|
| 1  | alice    | 100.00 EUR       |
| 2  | bob      | 100.00 EUR       |
| 3  | charlie  | 100.00 EUR       |

---

## API Reference

### Base URL
```
http://localhost:8080/api/v1
```

### Endpoints Overview

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/events` | List F1 events/sessions with driver markets & odds |
| `POST` | `/api/v1/bets` | Place a bet on a driver for a session |
| `GET` | `/api/v1/bets/{id}` | Get bet details by ID |
| `GET` | `/api/v1/users/{userId}/bets` | Get all bets placed by a user |
| `POST` | `/api/v1/events/{sessionKey}/outcome` | Record race outcome and trigger bet settlement |
| `GET` | `/api/v1/users` | List all registered users and current balances |
| `GET` | `/api/v1/users/{id}` | Get specific user by ID |
| `GET` | `/actuator/health` | Application health status |

---

### 1. List F1 Events

Fetches sessions from OpenF1 (or fallback), filters out cancelled sessions, and attaches a driver market with odds (2, 3, or 4) to each driver.

```http
GET /api/v1/events
```

**Query Parameters** (all optional, combinable):

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `sessionType` | string | Filter by session type | `Race`, `Qualifying`, `Practice` |
| `year` | integer | Filter by season year | `2023`, `2024` |
| `country` | string | Filter by country name | `Italy`, `Monaco`, `United Kingdom` |

**Example Requests:**
```bash
# All events
curl http://localhost:8080/api/v1/events

# 2023 races only
curl "http://localhost:8080/api/v1/events?sessionType=Race&year=2023"

# Italian GP events
curl "http://localhost:8080/api/v1/events?country=Italy"

# 2024 Monaco race
curl "http://localhost:8080/api/v1/events?sessionType=Race&year=2024&country=Monaco"
```

**Response (200 OK):**
```json
[
  {
    "sessionKey": "9157",
    "sessionName": "Race",
    "sessionType": "Race",
    "year": 2023,
    "countryName": "Italy",
    "circuitName": "Monza",
    "location": "Monza",
    "dateStart": "2023-09-03T13:00:00Z",
    "dateEnd": "2023-09-03T15:00:00Z",
    "driverMarket": [
      {
        "driverNumber": 1,
        "fullName": "Max VERSTAPPEN",
        "nameAcronym": "VER",
        "teamName": "Red Bull Racing",
        "odds": 3
      },
      {
        "driverNumber": 44,
        "fullName": "Lewis HAMILTON",
        "nameAcronym": "HAM",
        "teamName": "Mercedes",
        "odds": 4
      }
    ]
  }
]
```

---

### 2. Place a Bet

Places a bet on a driver to WIN the specified session. The driver is validated against the official session roster, and the stake is deducted from the user's balance atomically.

```http
POST /api/v1/bets
Content-Type: application/json
```

**Request Body:**
```json
{
  "userId": 1,
  "sessionKey": "9157",
  "driverNumber": 1,
  "stakeAmount": 20.00
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | Long | ✅ | ID of the user placing the bet |
| `sessionKey` | String | ✅ | Session key from `GET /api/v1/events` |
| `driverNumber` | Integer | ✅ | Car number of the backed driver (must be registered in session) |
| `stakeAmount` | BigDecimal | ✅ | Stake amount in EUR (min: 0.01) |

**Response (201 Created):**
```json
{
  "id": 1,
  "userId": 1,
  "username": "alice",
  "sessionKey": "9157",
  "driverNumber": 1,
  "odds": 3,
  "stakeAmount": 20.00,
  "potentialPayout": 60.00,
  "status": "PENDING",
  "placedAt": "2024-01-15T10:30:00",
  "settledAt": null
}
```

**Validation & Error Responses:**
- `400 Bad Request`: Validation failure (missing fields, non-positive stake, malformed JSON).
- `404 Not Found`: User does not exist.
- `422 Unprocessable Entity`: 
  - User has insufficient balance.
  - Event already has a recorded outcome (no betting on completed events).
  - Driver is not participating in the session.

---

### 3. Record Event Outcome & Settle Bets

Records the winner of an event and automatically settles all pending bets. Declared winner is validated against the session roster. Winners receive `stakeAmount × odds` credited to their balance.

```http
POST /api/v1/events/{sessionKey}/outcome
Content-Type: application/json
```

**Request Body:**
```json
{
  "winnerDriverNumber": 1
}
```

**Response (200 OK):**
```json
{
  "sessionKey": "9157",
  "winnerDriverNumber": 1,
  "totalBetsSettled": 2,
  "betsWon": 1,
  "betsLost": 1,
  "totalPaidOut": 60.00,
  "settlements": [
    {
      "betId": 1,
      "userId": 1,
      "username": "alice",
      "stakeAmount": 20.00,
      "payout": 60.00,
      "status": "WON"
    },
    {
      "betId": 2,
      "userId": 2,
      "username": "bob",
      "stakeAmount": 15.00,
      "payout": 0.00,
      "status": "LOST"
    }
  ]
}
```

**Error Responses:**
- `400 Bad Request`: Non-positive `winnerDriverNumber` or malformed request body.
- `422 Unprocessable Entity`: 
  - Winner driver did not race in this session.
  - Outcome has already been recorded for this session (idempotency guard).

---

### 4. Users & Balances

```bash
# List all users with current balances
GET /api/v1/users

# Get a specific user by ID
GET /api/v1/users/{id}
```

---

### 5. Bets History

```bash
# Get bet by ID
GET /api/v1/bets/{id}

# Get all bets placed by a user
GET /api/v1/users/{userId}/bets
```

---

## End-to-End Walkthrough

Here is a full cURL workflow testing the betting lifecycle:

```bash
BASE=http://localhost:8080/api/v1

# 1. Check initial user balances (Alice has 100 EUR, Bob has 100 EUR)
curl -s $BASE/users

# 2. Browse available events
curl -s "$BASE/events?sessionType=Race&year=2023&country=Italy"
# Note sessionKey (e.g. "9157") and driverNumber (e.g. 1 for Verstappen, 44 for Hamilton)

# 3. Alice (userId=1) places a 20 EUR bet on Max Verstappen (driver #1)
curl -s -X POST $BASE/bets \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"sessionKey":"9157","driverNumber":1,"stakeAmount":20.00}'
# Alice balance is now 80.00 EUR; bet status is PENDING

# 4. Bob (userId=2) places a 15 EUR bet on Lewis Hamilton (driver #44)
curl -s -X POST $BASE/bets \
  -H "Content-Type: application/json" \
  -d '{"userId":2,"sessionKey":"9157","driverNumber":44,"stakeAmount":15.00}'
# Bob balance is now 85.00 EUR; bet status is PENDING

# 5. Review Alice's active bets
curl -s $BASE/users/1/bets

# 6. Record race outcome — Driver #1 (Verstappen) wins!
curl -s -X POST $BASE/events/9157/outcome \
  -H "Content-Type: application/json" \
  -d '{"winnerDriverNumber":1}'
# Alice's bet is marked WON and paid out (e.g. 20 * 3 = 60 EUR credited)
# Bob's bet is marked LOST

# 7. Verify updated balances
curl -s $BASE/users/1  # Balance: 140.00 EUR (80 + 60)
curl -s $BASE/users/2  # Balance: 85.00 EUR
```

---

## Error Handling

All error responses adhere to a consistent, safe JSON payload structure without leaking database internals or stack traces:

```json
{
  "status": 422,
  "error": "Betting Rule Violation",
  "message": "Insufficient balance. Available: 5.00 EUR, Required: 20.00 EUR.",
  "timestamp": "2024-01-15T10:30:00",
  "details": null
}
```

Standard error codes:
- `400 Bad Request`: Field validation error, malformed JSON body, or parameter type mismatch.
- `404 Not Found`: Entity not found.
- `409 Conflict`: Database unique constraint or data conflict.
- `422 Unprocessable Entity`: Business rule violation (unregistered driver, outcome already recorded, insufficient balance).
- `502 Bad Gateway`: External F1 API communication failure.
- `500 Internal Server Error`: Generic internal error message (internal exception messages masked).

---

## Test Suite

The project includes **48 unit and integration tests** covering all critical business rules, concurrency scenarios, rate limiting, and failure handling:

```bash
mvn test
```

Test coverage includes:
- **`OutcomeServiceTest`**: Winner driver validation, win/loss bet settlements, user balance crediting, TOCTOU idempotency, and data integrity conflict handling.
- **`OutcomeControllerTest`**: HTTP validation, winner input bounds checking, and malformed JSON parsing.
- **`BettingServiceTest`**: Atomic balance debits, pessimistic lock execution, external driver validation, cancellation on timeout, and concurrent outcome race detection.
- **`GlobalExceptionHandlerTest`**: Sanitized generic 500 responses, 409 conflict mapping, malformed JSON, and parameter type mismatch handling.
- **`OpenF1EventProviderTest`**: Bounded LRU cache eviction under load, cancelled session filtering, driver deduplication, null team name fallback, and 429 cooldowns.
- **`OpenF1RateLimiterTest`**: Sliding window cap enforcement, Resilience4j circuit breaker success and failure tracking, and 429 backoff.
- **`OpenF1ClientTest`**: REST URL building, query encoding, and 404 handling.
- **`EventControllerTest`**: MockMvc integration test for event market generation.
- **`EventServiceTest`**: Bulk listing baseline optimization and driver market odds building.
- **`UserTest`**: Domain balance debit/credit invariants and validation.

---

## Extensibility

### Implementing a Custom F1 Data Provider
1. Implement the `F1EventProvider` interface:
   ```java
   @Component
   @Primary  // Takes precedence over OpenF1EventProvider
   public class CustomF1Provider implements F1EventProvider {
       @Override
       public List<F1Session> getSessions(SessionFilter filter) {
           // Custom provider logic
       }

       @Override
       public List<F1Driver> getDriversForSession(String sessionKey) {
           // Custom driver lookup
       }
   }
   ```
2. No service, domain, or controller modifications are needed.

### Switching Databases
To switch from in-memory H2 to a persistent database (e.g., PostgreSQL, MySQL):
1. Add the corresponding JDBC driver dependency to `pom.xml`.
2. Update `spring.datasource.*` and `spring.jpa.database-platform` in `application.properties`.

---

## Project Structure

```
f1-betting-app/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/f1betting/
    │   │   ├── F1BettingApplication.java
    │   │   ├── adapter/
    │   │   │   ├── F1EventProvider.java             # Interface for F1 data providers
    │   │   │   ├── mock/
    │   │   │   │   └── MockF1EventProvider.java     # Fallback provider with historical sessions
    │   │   │   ├── model/
    │   │   │   │   ├── F1Driver.java                # Agnostic driver model
    │   │   │   │   ├── F1Session.java               # Agnostic session model
    │   │   │   │   └── SessionFilter.java           # Filter criteria
    │   │   │   └── openf1/
    │   │   │       ├── OpenF1Client.java            # RestTemplate HTTP client
    │   │   │       ├── OpenF1EventProvider.java     # Primary provider with bounded LRU & fallback
    │   │   │       ├── OpenF1RateLimiter.java       # Token bucket & sliding window rate limiter
    │   │   │       └── dto/
    │   │   │           ├── OpenF1DriverDto.java     # OpenF1 driver payload
    │   │   │           └── OpenF1SessionDto.java    # OpenF1 session payload (is_cancelled mapping)
    │   │   ├── config/
    │   │   │   ├── AppConfig.java                   # RestTemplate, validation executor & TransactionTemplate
    │   │   │   └── DataInitializer.java             # Seeds initial users & balances
    │   │   ├── controller/
    │   │   │   ├── BetController.java               # Bet placement & user bet history
    │   │   │   ├── EventController.java             # Event listings with driver market
    │   │   │   ├── OutcomeController.java           # Outcome recording & bet settlement
    │   │   │   └── UserController.java              # User details & wallet balances
    │   │   ├── domain/
    │   │   │   ├── Bet.java                         # Bet JPA entity
    │   │   │   ├── EventOutcome.java                # EventOutcome JPA entity (unique session_key)
    │   │   │   └── User.java                        # User JPA entity
    │   │   ├── dto/
    │   │   │   ├── BetDto.java
    │   │   │   ├── DriverMarketDto.java
    │   │   │   ├── EventDto.java
    │   │   │   ├── OutcomeResultDto.java
    │   │   │   ├── PlaceBetRequest.java
    │   │   │   ├── RecordOutcomeRequest.java
    │   │   │   └── UserDto.java
    │   │   ├── enums/
    │   │   │   └── BetStatus.java                   # PENDING, WON, LOST
    │   │   ├── exception/
    │   │   │   ├── BettingException.java
    │   │   │   ├── ErrorResponse.java
    │   │   │   ├── GlobalExceptionHandler.java      # Centralized exception mapping
    │   │   │   └── ResourceNotFoundException.java
    │   │   ├── repository/
    │   │   │   ├── BetRepository.java
    │   │   │   ├── EventOutcomeRepository.java
    │   │   │   └── UserRepository.java
    │   │   └── service/
    │   │       ├── BettingService.java              # Bet placement with decoupled external validation
    │   │       ├── EventService.java                # Session querying & driver market generation
    │   │       ├── OutcomeService.java              # Winner validation, outcome recording & bet payout
    │   │       ├── SessionLockManager.java          # Fine-grained session locking for race prevention
    │   │       └── UserService.java                 # User lookups
    │   └── resources/
    │       └── application.properties               # H2, JPA, OpenF1, Rate limiter & Cache config
    └── test/
        └── java/com/f1betting/
            ├── adapter/openf1/
            │   ├── OpenF1ClientTest.java            # Client HTTP & 404 tests
            │   ├── OpenF1EventProviderTest.java     # Caching, bounded LRU, 429 backoff & fallback
            │   └── OpenF1RateLimiterTest.java       # Rate limiter, circuit breaker & sliding window tests
            ├── controller/
            │   ├── EventControllerTest.java         # MockMvc event listing integration tests
            │   └── OutcomeControllerTest.java       # MockMvc outcome & settlement endpoint tests
            ├── domain/
            │   └── UserTest.java                    # User balance debit/credit unit tests
            ├── exception/
            │   └── GlobalExceptionHandlerTest.java  # Security masking, 409 conflict & 400 error tests
            └── service/
                ├── BettingServiceTest.java          # Bet placement, lock & external validation tests
                ├── EventServiceTest.java            # Event service & baseline grid tests
                └── OutcomeServiceTest.java          # Winner validation, settlement & TOCTOU tests
```
