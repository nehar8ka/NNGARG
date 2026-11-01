# F1 Betting Backend

A Formula 1 betting REST API built with **Spring Boot 3**, **Oracle DB**, and the live **OpenF1 public API**.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    REST Controllers                      │
│   EventController  BetController  OutcomeController     │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                     Service Layer                        │
│      EventService   BettingService   OutcomeService      │
└────────────┬─────────────────────────────┬──────────────┘
             │                             │
┌────────────▼────────────┐  ┌────────────▼──────────────┐
│   F1EventProvider       │  │   JPA Repositories        │
│   (interface)           │  │   (Oracle DB)             │
│                         │  │                           │
│  └─ OpenF1EventProvider │  │  UserRepository           │
│     └─ OpenF1Client     │  │  BetRepository            │
│        (RestTemplate)   │  │  EventOutcomeRepository   │
└─────────────────────────┘  └───────────────────────────┘
         ↕ HTTP
  api.openf1.org/v1
```

**Key design principle**: All application code depends on the `F1EventProvider` interface, not on OpenF1 directly. Swapping to a different F1 data source requires only a new interface implementation — zero changes to services or controllers.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Oracle DB | 12c+ (for SEQUENCE support) |

---

## Database Setup (Oracle)

Run the following SQL as a DBA to create the application schema:

```sql
-- Create the application user/schema
CREATE USER f1betting IDENTIFIED BY password;
GRANT CONNECT, RESOURCE TO f1betting;
GRANT CREATE SEQUENCE TO f1betting;
GRANT UNLIMITED TABLESPACE TO f1betting;
```

Hibernate will auto-create all tables and sequences on first startup (`ddl-auto=update`).

---

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# Point to your Oracle instance
spring.datasource.url=jdbc:oracle:thin:@localhost:1521/XEPDB1
spring.datasource.username=f1betting
spring.datasource.password=password
```

Common Oracle URL formats:
- **XE (pluggable)**: `jdbc:oracle:thin:@localhost:1521/XEPDB1`
- **SID style**: `jdbc:oracle:thin:@localhost:1521:XE`
- **TNS**: `jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=host)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))`

---

## Building & Running

```bash
# Clone / navigate to project root
cd f1-betting-app

# Build the JAR (skip tests to avoid needing a DB at build time)
mvn clean package -DskipTests

# Run
java -jar target/f1-betting-app-1.0.0.jar
```

Or run directly via Maven:

```bash
mvn spring-boot:run
```

The application starts on **http://localhost:8080**.

On first startup, three users are automatically seeded with **100 EUR** each:

| ID | Username | Starting Balance |
|----|----------|-----------------|
| 1  | alice    | 100.00 EUR |
| 2  | bob      | 100.00 EUR |
| 3  | charlie  | 100.00 EUR |

---

## API Reference

### Base URL
```
http://localhost:8080/api/v1
```

---

### 1. List F1 Events

Fetches live session data from OpenF1 and attaches a driver market (with random odds 2/3/4) to each event.

```
GET /api/v1/events
```

**Query Parameters** (all optional, combinable):

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `sessionType` | string | Filter by session type | `Race`, `Qualifying`, `Practice` |
| `year` | integer | Filter by season year | `2023`, `2024` |
| `country` | string | Filter by country name | `Italy`, `Monaco` |

**Examples:**
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
    "sessionKey": "9161",
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

> **Note**: `odds` are randomly generated each request (2, 3, or 4). The odds assigned at **bet placement time** are stored with the bet and used for settlement.

---

### 2. Place a Bet

Places a single bet on a driver to WIN the specified F1 event. The stake is immediately deducted from the user's balance.

```
POST /api/v1/bets
Content-Type: application/json
```

**Request Body:**
```json
{
  "userId": 1,
  "sessionKey": "9161",
  "driverNumber": 1,
  "stakeAmount": 20.00
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | long | ✅ | ID of the user placing the bet |
| `sessionKey` | string | ✅ | Session key from GET /api/v1/events |
| `driverNumber` | integer | ✅ | Driver's car number |
| `stakeAmount` | decimal | ✅ | Amount in EUR (min 0.01) |

**Response (201 Created):**
```json
{
  "id": 1,
  "userId": 1,
  "username": "alice",
  "sessionKey": "9161",
  "driverNumber": 1,
  "odds": 3,
  "stakeAmount": 20.00,
  "potentialPayout": 60.00,
  "status": "PENDING",
  "placedAt": "2024-01-15T10:30:00",
  "settledAt": null
}
```

**Error responses:**
- `400` — Validation failed (missing/invalid fields)
- `404` — User not found
- `422` — Insufficient balance / event already has result / driver not in session

---

### 3. Record Event Outcome

Records who won an F1 event and automatically settles all pending bets. Winners have their payout credited to their balance.

```
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
  "sessionKey": "9161",
  "winnerDriverNumber": 1,
  "totalBetsSettled": 5,
  "betsWon": 2,
  "betsLost": 3,
  "totalPaidOut": 120.00,
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
      "stakeAmount": 10.00,
      "payout": 0.00,
      "status": "LOST"
    }
  ]
}
```

**Error responses:**
- `422` — Outcome already recorded for this session

---

### 4. Get Users

```bash
# List all users (useful to check current balance)
GET /api/v1/users

# Get specific user
GET /api/v1/users/{id}
```

---

### 5. Get Bets

```bash
# Get a specific bet
GET /api/v1/bets/{id}

# List all bets for a user
GET /api/v1/users/{userId}/bets
```

---

## End-to-End Walkthrough

```bash
BASE=http://localhost:8080/api/v1

# 1. Check users and their balances
curl $BASE/users

# 2. Find an F1 event to bet on
curl "$BASE/events?sessionType=Race&year=2023&country=Italy"
# Note the sessionKey (e.g. "9161") and a driverNumber from driverMarket

# 3. Alice (userId=1) bets €20 on driver #1 to win
curl -X POST $BASE/bets \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"sessionKey":"9161","driverNumber":1,"stakeAmount":20.00}'
# Alice's balance is now 80.00 EUR; bet status is PENDING

# 4. Bob (userId=2) bets €15 on driver #44
curl -X POST $BASE/bets \
  -H "Content-Type: application/json" \
  -d '{"userId":2,"sessionKey":"9161","driverNumber":44,"stakeAmount":15.00}'

# 5. Record the race outcome — driver #1 won
curl -X POST $BASE/events/9161/outcome \
  -H "Content-Type: application/json" \
  -d '{"winnerDriverNumber":1}'
# Alice's bet is WON; her balance is credited with stakeAmount × odds
# Bob's bet is LOST

# 6. Verify Alice's updated balance
curl $BASE/users/1
```

---

## Error Response Format

All errors return a consistent JSON structure:

```json
{
  "status": 422,
  "error": "Betting Rule Violation",
  "message": "Insufficient balance. Available: 5.00 EUR, Required: 20.00 EUR.",
  "timestamp": "2024-01-15T10:30:00",
  "details": null
}
```

---

## Extensibility: Adding a New F1 Data Provider

1. Implement the `F1EventProvider` interface:
   ```java
   @Component
   @Primary  // Override the OpenF1 default
   public class MyNewF1Provider implements F1EventProvider {
       @Override
       public List<F1Session> getSessions(SessionFilter filter) { ... }

       @Override
       public List<F1Driver> getDriversForSession(String sessionKey) { ... }
   }
   ```

2. Register it as a Spring bean. No other code changes are needed.

---

## Project Structure

```
src/main/java/com/f1betting/
├── F1BettingApplication.java
├── adapter/
│   ├── F1EventProvider.java          ← Interface (swap providers here)
│   ├── model/
│   │   ├── F1Session.java            ← Provider-agnostic session model
│   │   ├── F1Driver.java             ← Provider-agnostic driver model
│   │   └── SessionFilter.java        ← Filter criteria
│   └── openf1/
│       ├── OpenF1EventProvider.java  ← OpenF1 implementation
│       ├── OpenF1Client.java         ← HTTP calls to api.openf1.org
│       └── dto/
│           ├── OpenF1SessionDto.java ← Raw API response shape
│           └── OpenF1DriverDto.java  ← Raw API response shape
├── config/
│   ├── AppConfig.java                ← RestTemplate bean
│   └── DataInitializer.java          ← Seeds 3 users with 100 EUR each
├── controller/
│   ├── EventController.java          ← GET /api/v1/events
│   ├── BetController.java            ← POST /api/v1/bets
│   ├── OutcomeController.java        ← POST /api/v1/events/{key}/outcome
│   └── UserController.java           ← GET /api/v1/users
├── domain/
│   ├── User.java
│   ├── Bet.java
│   └── EventOutcome.java
├── dto/                              ← Request/Response DTOs
├── enums/
│   └── BetStatus.java                ← PENDING / WON / LOST
├── exception/
│   └── GlobalExceptionHandler.java   ← Unified error handling
├── repository/                       ← Spring Data JPA repositories
└── service/
    ├── EventService.java
    ├── BettingService.java
    ├── OutcomeService.java
    └── UserService.java
```
