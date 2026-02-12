## Overview

Multithreaded WebSocket client that simulates 500,000 concurrent chat messages to test server performance.

## Features

- Two-phase testing (Warmup + Main Load)
- Producer-Consumer pattern with thread-safe queue
- Connection pooling (one connection per room)
- Exponential backoff retry mechanism
- Basic performance metrics output

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
WARMUP_THREADS = 32;                            
MAIN_PHASE_THREADS = 200;                        
```

## Build
```bash
mvn clean package
```

Generates: `target/chatflow-client-part1-jar-with-dependencies.jar`

## Run
```bash
java -jar target/chatflow-client-part1-jar-with-dependencies.jar
```

**Expected Runtime:** 3-10 seconds for 500,000 messages

## Output
```
========== Test Summary ==========
Successful messages: 500000
Failed messages: 0
Total runtime: 3318 ms
Throughput: 150693.19 msg/sec
Total connections: 76
Reconnections: 0
==================================
```

## Performance

**Achieved Results:**
- **Throughput:** 150,693 msg/sec
- **Success Rate:** 100%
- **Runtime:** 3.3 seconds
- **Optimization:** Connection pooling (20-76 connections vs 500,000)

## Test Phases

### Phase 1: Warmup
- **Threads:** 32
- **Messages per thread:** 1,000
- **Total:** 32,000 messages
- **Purpose:** Establish baseline performance

### Phase 2: Main Load  
- **Threads:** 100-200 (configurable)
- **Total:** 468,000 messages
- **Purpose:** Sustained high-load testing

## Technology Stack

- Java 17
- Java-WebSocket 1.5.4
- Jackson 2.15.3
- Maven Assembly Plugin
