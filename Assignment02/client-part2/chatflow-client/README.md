## Overview

Enhanced load testing client with detailed latency measurement, statistical analysis, and throughput visualization.

## Features

- All Client Part 1 features
- Per-message latency tracking (10% sampling)
- CSV export (500,000 records)
- Statistical analysis (mean, median, percentiles)
- Throughput visualization (line chart)
- Room and message type distribution analysis

## Prerequisites
```bash
java -version  # Requires Java 17+
mvn -version   # Requires Maven 3.8+
```

## Configuration

Edit `src/main/java/com/chatflow/client/config/ClientConfig.java`:
```java
SERVER_URL = "ws://52.90.172.30:8080/chat/";
TOTAL_MESSAGES = 500_000;
ENABLE_DETAILED_METRICS = true;   
ENABLE_VISUALIZATION = true;       
CSV_OUTPUT_FILE = "results/performance-metrics.csv";
CHART_OUTPUT_FILE = "results/throughput-chart.png";
```

## Build
```bash
mvn clean package
```

Generates: `target/chatflow-client-part2-jar-with-dependencies.jar`

## Run
```bash
java -jar target/chatflow-client-part2-jar-with-dependencies.jar
```

**Expected Runtime:** 3-5 minutes for 500,000 messages

## Output

### Basic Metrics
```
Successful messages: 495481
Failed messages: 4519
Total runtime: 216130 ms
Throughput: 2293.54 msg/sec
```

### Detailed Statistics
```
Response Time Statistics:
  (Based on 23360 measured samples)
  Mean response time: 47 ms
  Median response time: 14 ms
  95th percentile: 100 ms
  99th percentile: 886 ms
  Min response time: 1 ms
  Max response time: 2983 ms

Throughput per Room:
  Room 1-20: ~25,000 messages each

Message Type Distribution:
  TEXT: 450,000 (90%)
  JOIN: 25,000 (5%)
  LEAVE: 25,000 (5%)
```

### Generated Files
- `results/performance-metrics.csv` - 500,000 message records
- `results/throughput-chart.png` - Throughput over time visualization

## Sampling Strategy

**For performance efficiency:**
- **10% of messages (50,000):** Synchronous send with latency measurement
- **90% of messages (450,000):** Asynchronous fast-send (latency=0 in CSV)

**Rationale:** Provides statistically significant latency data (n=50,000) while maintaining reasonable throughput (~2,300 msg/sec vs ~150 msg/sec if all messages measured).

## CSV Format
```csv
timestamp,messageType,latencyMs,statusCode,roomId
1707534567890,TEXT,68,200,5
1707534567891,TEXT,0,200,12
...
```

- **latencyMs > 0:** Measured latency
- **latencyMs = 0:** Fast-send (not measured)
- **statusCode:** 200 (success), 400 (validation error), 500 (server error)

## Visualization

Throughput chart shows messages/second over time in 10-second buckets:
- X-axis: Time (seconds)
- Y-axis: Messages per second
- Demonstrates performance pattern: ramp-up → peak → ramp-down

## Technology Stack

- Java 17
- Java-WebSocket 1.5.4
- Jackson 2.15.3
- Apache Commons CSV 1.10.0
- JFreeChart 1.5.4