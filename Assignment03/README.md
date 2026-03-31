# CS6650 Assignment 3: Persistence and Data Management

## Overview

This assignment adds persistent storage to the ChatFlow distributed chat system built in Assignments 1 and 2. Messages are persisted to PostgreSQL at high throughput while maintaining real-time broadcast performance. A Metrics API exposes query results for core and analytics queries.

**System:** WebSocket Client → ALB → server-v2 → RabbitMQ → consumer-v3 → PostgreSQL

---

## Repository Structure

```
Assignment03/
├── server-v2/                  # WebSocket server with Metrics API
│   └── chatflow-server/
├── consumer-v3/                # RabbitMQ consumer with PostgreSQL persistence
│   └── chatflow-consumer/
├── client-part2/               # Multithreaded load test client
│   └── chatflow-client/
├── database/                   # Schema files and setup scripts
│   ├── schema.sql
│   ├── setup.sh
│   └── README.md
├── monitoring/                 # Metrics collection scripts
│   ├── collect_pg_stats.sh
│   ├── endurance_monitor.sh
│   ├── check-health.sh
│   ├── check-rabbitmq.sh
│   └── README.md
└── load-tests/                 # Test configurations and results
    ├── baselineTest/           # 500K messages, unthrottled
    ├── stressTest/             # 1M messages, unthrottled
    └── enduranceTest/          # 2M messages, rate-limited 1,040 msg/s
```

---

## EC2 Infrastructure

| Component | Instance | Private IP | Public IP |
|-----------|----------|-----------|-----------|
| server-v2 | t3.small | 172.31.12.21 | 3.228.13.72 |
| consumer-v3 | t3.micro | 172.31.25.161 | 34.229.143.25 |
| PostgreSQL 15 | t3.micro | 172.31.19.82 | 3.85.198.180 |
| RabbitMQ 3.12 | t3.micro | 172.31.18.27 | — |
| ALB | — | — | chatflow-alb-1683935178.us-east-1.elb.amazonaws.com |

---

## Quick Start

### 1. Database Setup

```bash
# On PostgreSQL EC2
sudo bash database/setup.sh

# Verify
psql -U chatflow -d chatflow -c "\d messages"
psql -U chatflow -d chatflow -c "\di"
```

### 2. Start Consumer

```bash
# On consumer EC2
cd consumer-v3/chatflow-consumer
mvn clean package -DskipTests
java -jar target/chatflow-consumer-jar-with-dependencies.jar

# Verify
curl http://localhost:8081/health | python3 -m json.tool
```

### 3. Start Server

```bash
# On server EC2
cd server-v2/chatflow-server
mvn clean package -DskipTests
java -jar target/chatflow-server-jar-with-dependencies.jar

# Verify
curl http://localhost:8080/health
```

### 4. Run Load Test

```bash
# On local machine
cd client-part2/chatflow-client
mvn clean package -DskipTests

# Test 1: Baseline (500K, unthrottled)
# In ClientConfig.java: TOTAL_MESSAGES=500000, TARGET_PUBLISH_RATE_PER_SEC=0
java -jar target/chatflow-client-part2-jar-with-dependencies.jar

# Test 2: Stress (1M, unthrottled)
# In ClientConfig.java: TOTAL_MESSAGES=1000000, TARGET_PUBLISH_RATE_PER_SEC=0
java -jar target/chatflow-client-part2-jar-with-dependencies.jar

# Test 3: Endurance (2M, rate-limited)
# In ClientConfig.java: TOTAL_MESSAGES=2000000, TARGET_PUBLISH_RATE_PER_SEC=1040
java -jar target/chatflow-client-part2-jar-with-dependencies.jar
```

---

## Key Configuration

### consumer-v3 `application.yml`

```yaml
rabbitmq:
  consumer-thread-count: 20       # RabbitMQ consumer workers (= queue count)
  consumer-prefetch: 100          # Messages prefetched per consumer

database:
  batch-size: 1000                # JDBC batch insert size
  flush-interval-ms: 200          # Max wait before flush (ms)
  writer-thread-count: 20         # DB writer threads
  circuit-breaker:
    failure-threshold: 5          # Failures before OPEN state
    reset-timeout-ms: 30000       # Time before HALF_OPEN probe (ms)

spring:
  datasource:
    hikari:
      maximum-pool-size: 30       # HikariCP max connections
      minimum-idle: 5
      connection-timeout: 3000
```

### client-part2 `ClientConfig.java`

```java
TOTAL_MESSAGES = 500_000          // Total messages to send
WARMUP_THREADS = 32               // Threads in warmup phase
MAIN_PHASE_THREADS = 128          // Threads in main phase
TARGET_PUBLISH_RATE_PER_SEC = 0   // 0 = unthrottled, 1040 = Test 3
MAX_RETRIES = 5                   // Retry attempts per message
INITIAL_BACKOFF_MS = 50           // Initial backoff (doubles each retry)
```

---

## Database Design

**Table:** `messages` (PostgreSQL 15)

| Key Column | Type | Purpose |
|------------|------|---------|
| `id` | BIGSERIAL PK | Surrogate key, avoids UUID B-tree fragmentation |
| `message_id` | VARCHAR(36) UNIQUE | Idempotent writes via ON CONFLICT DO NOTHING |
| `room_id` | VARCHAR(20) | Composite index leading column |
| `user_id` | VARCHAR(10) | High-selectivity user queries |
| `event_time` | TIMESTAMPTZ | Range queries and DATE_TRUNC aggregation |
| `received_at` | TIMESTAMPTZ | Pipeline ingestion latency tracking |

**Indexes:**
- `idx_room_event_time (room_id, event_time)` — Core Q1
- `idx_user_event_time (user_id, event_time)` — Core Q2, Q4
- `idx_event_time (event_time)` — Core Q3, Analytics Q1

**Write optimization:**
- `JdbcTemplate.batchUpdate()` replaces JPA saveAll() — bypasses Hibernate IDENTITY restriction
- `synchronous_commit = off` — eliminates per-commit WAL disk sync
- Result: 300–380 msg/s → 1,200–1,250 msg/s (+4x)

---

## Metrics API

**Endpoint:** `GET /metrics?roomId={id}`

Called automatically by the client after each test completes. Returns results for all 4 Core Queries and 4 Analytics Queries directly from PostgreSQL.

```bash
curl "http://<alb-dns>/metrics?roomId=1" | python3 -m json.tool
```

**Response includes:**
- `totalMessages` — total rows in messages table
- `coreQueries` — room messages in range, user history, active users, rooms for user
- `analyticsQueries` — messages/sec, top active users, top active rooms, user participation patterns

---

## Load Test Results Summary

| Test | Messages | Duration | DB Written | Peak Throughput | p99 Latency |
|------|----------|----------|-----------|----------------|-------------|
| Baseline | 500K | ~8 min | 500,490 ✅ | 1,283/s | 44ms |
| Stress | 1M | ~23 min | 1,001,038 ✅ | 1,328/s | 31ms |
| Endurance | 2M | 35 min | 2,001,642 ✅ | 1,309/s | 30ms |

Zero message loss across all tests. Zero bufferFullNacks, dbFailed, or circuit breaker trips.

---

## Monitoring

See `/monitoring/README.md` for full usage instructions.

```bash
# Collect PostgreSQL metrics during test (run on PG EC2)
bash monitoring/collect_pg_stats.sh > pg_stats_baseline.txt &

# Comprehensive per-minute monitoring (run on consumer EC2, endurance test only)
bash monitoring/endurance_monitor.sh > endurance_monitor.txt &

# Ad-hoc health check
bash monitoring/check-health.sh

# Ad-hoc RabbitMQ queue depths
bash monitoring/check-rabbitmq.sh
```

---
