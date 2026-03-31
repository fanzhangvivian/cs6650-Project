# Monitoring Scripts

Scripts used to collect metrics during CS6650 Assignment 3 load tests.
All output files are stored in `/load-tests/` under the corresponding test subdirectory.

---

## Scripts Overview

| Script | Purpose | Output File |
|--------|---------|-------------|
| `collect_pg_stats.sh` | PostgreSQL metrics every 10 seconds | `pg_stats_*.txt` |
| `endurance_monitor.sh` | Comprehensive per-minute snapshot (endurance test only) | `endurance_monitor.txt` |
| `check-health.sh` | Ad-hoc health check for all instances | console only |
| `check-rabbitmq.sh` | Ad-hoc RabbitMQ queue depths for all 20 rooms | console only |

---

## collect_pg_stats.sh

Collects PostgreSQL metrics every 10 seconds throughout a load test.

**What it captures per interval:**
- Total message count (`COUNT(*) FROM messages`)
- Active vs idle DB connections (`pg_stat_activity`)
- Index scan counts and tuple reads (`pg_stat_user_indexes`)
- Table total size and data size (`pg_total_relation_size`)
- Table bloat: `n_live_tup` and `n_dead_tup` (`pg_stat_user_tables`)

**Usage (run on PostgreSQL EC2):**
```bash
# Start collection
bash collect_pg_stats.sh > ~/pg_stats_baseline.txt 2>&1 &
echo $! > ~/collect_pg_stats.pid

# Stop collection
kill $(cat ~/collect_pg_stats.pid)

# Download results
scp -i ~/.ssh/labsuser.pem ec2-user@<pg-ip>:~/pg_stats_baseline.txt \
    load-tests/baselineTest/
```

**Environment variables (optional overrides):**
```bash
DB_HOST=172.31.19.82   # PostgreSQL private IP
DB_USER=chatflow
DB_NAME=chatflow
PGPASSWORD=chatflow123
INTERVAL=10            # seconds between snapshots
```

---

## endurance_monitor.sh

Comprehensive per-minute monitoring script used exclusively during Test 3 (Endurance).
Captures all metrics needed to detect memory leaks, connection pool exhaustion,
disk space issues, and performance degradation over time.

**What it captures per 60-second interval:**
- JVM heap stats: Survivor spaces, Eden, Old Gen, Metaspace, Young GC, Full GC, GC time (`jstat -gc`)
- System memory: total, used, free, swap (`free -m`)
- Consumer health: messagesConsumed, dbWritten, bufferFullNacks, writeLatencyMs p50/p95/p99, circuit breaker state
- PostgreSQL connections: active vs idle count (`pg_stat_activity`)
- Total messages persisted (`COUNT(*) FROM messages`)
- Disk usage: filesystem % and table size (`pg_total_relation_size`)
- Table bloat: `n_live_tup` and `n_dead_tup`

**Usage (run on consumer EC2):**
```bash
# Find consumer PID
CONSUMER_PID=$(pgrep -f chatflow-consumer)

# Start monitoring
bash endurance_monitor.sh > ~/endurance_monitor.txt 2>&1 &
echo $! > ~/endurance_monitor.pid

# Stop monitoring
kill $(cat ~/endurance_monitor.pid)

# Download results
scp -i ~/.ssh/labsuser.pem ec2-user@<consumer-ip>:~/endurance_monitor.txt \
    load-tests/enduranceTest/
```

**Environment variables (optional overrides):**
```bash
CONSUMER_PID=1005793   # auto-detected via pgrep if not set
CONSUMER_PORT=8081
DB_HOST=172.31.19.82
DB_USER=chatflow
DB_NAME=chatflow
PGPASSWORD=chatflow123
INTERVAL=60            # seconds between snapshots
```

---

## check-health.sh

Ad-hoc health check for all server instances and consumer.
Used to verify system status before and after tests.

**Usage:**
```bash
bash check-health.sh
```

---

## check-rabbitmq.sh

Ad-hoc queue depth check for all 20 RabbitMQ rooms via Management API.
Used to spot-check queue state during tests.

**Usage:**
```bash
bash check-rabbitmq.sh
```

---

## Manual Monitoring Endpoints

| Component | URL |
|-----------|-----|
| RabbitMQ Management | http://172.31.18.27:15672 |
| Consumer Health | http://34.229.143.25:8081/health |
| Server-v2 Health | http://3.228.13.72:8080/health |
| Metrics API | http://chatflow-alb-1683935178.us-east-1.elb.amazonaws.com/metrics?roomId=1 |
| ALB Console | AWS Console → EC2 → Load Balancers → chatflow-alb |
