# CS6650 Assignment 1: Design Document
**ChatFlow - Scalable WebSocket Chat System**


---

## 1. Architecture Overview

### System Architecture Diagram
```
┌─────────────── Load Test Client ────────────────┐
│ Generator(1) → Queue → Senders(32-200) → Pool  │
│   (Producer)  (10K)    (Consumers)    (20 conns)│
└─────────────────────────┬───────────────────────┘
                          │ WebSocket
                          ▼
┌──────────────── AWS EC2 Server ─────────────────┐
│ WebSocketHandler → Validator → ConnectionMgr   │
│ (Spring Boot + Tomcat, Port 8080)              │
└─────────────────────────────────────────────────┘
```

**Data Flow:** Generator produces messages → Queue buffers → Senders consume → Connection pool routes to server → Server validates and echoes → Metrics collected

---

## 2. Major Classes and Relationships

### 2.1 Server Classes (Part 1)
- **ChatWebSocketHandler:** Core handler, routes messages, manages WebSocket lifecycle
- **MessageValidator:** Validates userId (1-100K), username (3-20 chars), message (1-500 chars), ISO-8601 timestamp, messageType enum
- **ConnectionManager:** Thread-safe session tracking using ConcurrentHashMap<roomId, Map<sessionId, session>>
- **HealthController:** REST endpoint returning server status

### 2.2 Client Classes (Parts 1-3)
**Shared Components:**
- **LoadTestClient:** Main orchestrator, runs warmup and main phases
- **MessageGenerator:** Single producer thread, generates 500K messages from 50 predefined templates
- **MessageQueue:** Thread-safe BlockingQueue (capacity: 10,000)
- **ConnectionPool:** Maintains one WebSocket connection per room (max 20), reuses connections
- **ChatWebSocketClient:** Wraps Java-WebSocket library, handles connect/send/receive

**Part 1 Specific:**
- **MessageSender:** Consumer threads, fire-and-forget sending
- **BasicMetricsCollector:** Tracks success/failure counts, connections, throughput

**Part 2 Additions:**
- **DetailedMessageSender:** Synchronous sending with latency measurement (10% sampling)
- **DetailedMetricsCollector:** Records per-message latency using ConcurrentLinkedQueue
- **PerformanceAnalyzer:** Calculates mean, median, 95th/99th percentiles
- **CSVWriter:** Outputs 500K records to CSV

**Part 3 Addition:**
- **ThroughputChart:** Generates line chart from timestamp data (10-second buckets)

---

## 3. Threading Model

### 3.1 Two-Phase Design
**Phase 1 (Warmup):** 1 generator + 32 senders = 33 threads, sends 32,000 messages  
**Phase 2 (Main):** 1 generator + 100-200 senders, sends 468,000 messages

### 3.2 Synchronization
```java
BlockingQueue: Producer-Consumer pattern
  - put(): Blocks if full (backpressure control)
  - poll(30s): Timeout prevents deadlock

Thread-safe metrics:
  - AtomicInteger: success/failure counts
  - ConcurrentLinkedQueue: message records (Part 2)
  - ConcurrentHashMap: connection pool
```

### 3.3 Lifecycle
1. **Submit:** ExecutorService.submit(generator + senders)
2. **Wait:** generatorFuture.get() ensures all messages produced
3. **Complete:** All senderFutures.get() ensures all messages sent
4. **Shutdown:** executor.shutdown() with 60s timeout

---

## 4. WebSocket Connection Management

### 4.1 Connection Pool Strategy
**Design:** One persistent connection per room (ConcurrentHashMap<roomId, WebSocketClient>)

**Lifecycle:**
1. **First message to room:** Create connection, await handshake (5s timeout), store in pool
2. **Subsequent messages:** Retrieve from pool, validate isConnected(), reuse
3. **Completion:** pool.closeAll() gracefully closes all

**Impact:** Reduced connections from 500,000 to 20-76 (6,500x improvement), eliminated 7 hours of handshake overhead (50ms × 500K)

### 4.2 Latency Measurement (Part 2)
**Sampling Strategy (10%):** Synchronously measure 50,000 messages (send → wait for response → record latency), asynchronously fast-send 450,000 messages (latency=0 in CSV)

**Rationale:** Balances accuracy (50K samples >> statistical significance threshold) with efficiency (maintains 70% of async throughput)

---

## 5. Little's Law Analysis

### 5.1 Formula and Measurements
**Little's Law:** `L = λ × W` where L=concurrent items, λ=throughput, W=response time

**Measured RTT:** Connection: 50ms (one-time) + Send: 5ms + Process: 10ms + Receive: 5ms = **70ms total**

### 5.2 Predictions vs Actual

**Client Part 1 (Async):**
```
Prediction: λ = L/W = 20 connections / 0.005s = 4,000 msg/sec
            With 200 threads: ~40,000 msg/sec
Actual:     150,693 msg/sec (3.8x better)
Reason:     Zero wait time + optimized pooling
```

**Client Part 2 (Sync + Sampling):**
```
Prediction: λ = 20 / 0.070s = 286 msg/sec
            With sampling: ~2,000-3,000 msg/sec
Actual:     2,293 msg/sec (matches prediction!)
Reason:     10% sampling maintains 70% of async speed
```

**Validation:** Little's Law accurately predicts performance when wait time is properly accounted for. Connection pooling has exponential impact on throughput.


