# CS6650 Assignment 2: ChatFlow Architecture Document

## 1. System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         Clients (Mac)                           │
│              WebSocket connections via Java client              │
│          2 × 20 rooms = up to 40 connections                    │
└───────────────────────────┬─────────────────────────────────────┘
                            │ WebSocket (ws://)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              AWS Application Load Balancer (ALB)                │
│                   chatflow-alb (us-east-1)                      │
│         HTTP/WebSocket, 3 Availability Zones, Port 8080         │
│              Sticky sessions: disabled (stateless)              │
└──────┬──────────────┬──────────────┬──────────────┬─────────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
│  Server 1  │ │  Server 2  │ │  Server 3  │ │  Server 4  │
│ t3.micro   │ │ t3.micro   │ │ t3.micro   │ │ t3.micro   │
│Spring Boot │ │Spring Boot │ │Spring Boot │ │Spring Boot │
│  :8080     │ │  :8080     │ │  :8080     │ │  :8080     │
└─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
      │              │              │              │
      └──────────────┴──────┬───────┴──────────────┘
                            │ AMQP (publish)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    RabbitMQ (t2.micro)                          │
│              Exchange: chat.exchange (topic, durable)           │
│         20 durable queues: room.1 ~ room.20                     │
│    Routing key: room.{roomId}  |  TTL: 1h  |  Max: 100K msgs    │
└───────────────────────────┬─────────────────────────────────────┘
                            │ AMQP (consume)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Consumer (t2.micro)                          │
│              20 worker threads, prefetch=200                    │
│         Broadcasts via HTTP POST /internal/broadcast            │
└──────┬──────────────┬──────────────┬──────────────┬────────────┘
       │              │              │              │
       │ HTTP POST    │ HTTP POST    │ HTTP POST    │ HTTP POST
       ▼              ▼              ▼              ▼
   Server 1       Server 2       Server 3       Server 4
 (fanout to    (fanout to    (fanout to    (fanout to
  sessions)     sessions)     sessions)     sessions)
```

---

## 2. Message Flow Sequence Diagram

```
Client          ALB          Server-v2       RabbitMQ      Consumer
  │              │               │               │              │
  │─WebSocket──▶│               │               │              │
  │             │──route to────▶│               │              │
  │             │               │               │              │
  │─send msg───▶│               │               │              │
  │             │──forward─────▶│               │              │
  │             │               │─validate msg  │              │
  │             │               │─build QueueMsg│              │
  │             │               │─basicPublish─▶│              │
  │             │               │               │─deliver─────▶│
  │◀─SUCCESS────│◀──response────│               │              │
  │             │               │               │  basicAck───▶│
  │             │               │               │              │
  │             │               │               │  HTTP POST──▶│(Server 1..4)
  │             │               │◀──broadcast───────────────────│
  │             │               │─sendToSessions│              │
  │◀─broadcast──│◀──WebSocket───│               │              │
```

**Key design decision:** Server responds SUCCESS to client immediately after publishing to RabbitMQ, without waiting for consumer broadcast. This decouples producer latency from fan-out latency.

---

## 3. Queue Topology Design

```
Exchange: chat.exchange (type=topic, durable=true)

Binding pattern:
  room.1  ──binding(room.1)──▶  queue: room.1
  room.2  ──binding(room.2)──▶  queue: room.2
  ...
  room.20 ──binding(room.20)─▶  queue: room.20

Queue configuration (per queue):
  - Type:       classic, durable
  - x-message-ttl:  3,600,000 ms (1 hour)
  - x-max-length:   100,000 messages
  - Auto-delete:    false
  - Exclusive:      false

Total: 20 queues × 100K max = 2M message capacity
```

**Routing logic:** When server receives a message for roomId=5, it publishes with routing key `room.5`. Only the `room.5` queue receives the message — no cross-room delivery.

---

## 4. Consumer Threading Model

```
Consumer Application (t2.micro)
│
├── Worker Thread 0  ──▶  room.1,  room.3,  room.5, ... (odd rooms)
├── Worker Thread 1  ──▶  room.2,  room.4,  room.6, ... (even rooms)
├── Worker Thread 2  ──▶  room.1,  room.3,  ...
│   ...
└── Worker Thread 19 ──▶  room.20, ...

Distribution: Round-robin assignment
  workerIndex = (roomId - 1) % consumerThreadCount
  e.g., 20 queues / 20 threads = 1 queue per thread (at thread count=20)

Per-thread configuration:
  - Each thread owns exactly ONE Channel (thread-safe, no sharing)
  - basicQos(prefetch=200): each thread buffers up to 200 unacked messages
  - Manual acknowledgment (autoAck=false)
  - ACK sent before HTTP broadcast (decouples queue from network latency)

Deduplication:
  - ConcurrentHashSet tracks recent messageIds
  - Cleared when size exceeds 10,000 to prevent unbounded memory growth
```

---

## 5. Load Balancing Configuration

| Parameter | Value |
|-----------|-------|
| Type | AWS Application Load Balancer (ALB) |
| Scheme | Internet-facing |
| Protocol | HTTP / WebSocket upgrade |
| Port | 8080 |
| Availability Zones | us-east-1b, us-east-1d (3 AZs total) |
| Target type | Instance |
| Sticky sessions | Disabled |
| Health check path | `/health` |
| Health check interval | 30 seconds |
| Health check timeout | 5 seconds |
| Healthy threshold | 2 consecutive successes |
| Unhealthy threshold | 3 consecutive failures |
| Total targets (4-instance test) | 4 healthy (3 in us-east-1d, 1 in us-east-1b) |

**Note on sticky sessions:** Sticky sessions are disabled because clients maintain persistent WebSocket connections for the duration of the test. Once a connection is established to a target, it stays on that target for its lifetime. ALB distributes new connection requests across all healthy targets.

---

## 6. Failure Handling Strategies

### 6.1 Server-side: Circuit Breaker (RabbitMQ publish)
```
States: CLOSED → OPEN → HALF_OPEN → CLOSED

- CLOSED:    Normal operation, all publishes allowed
- OPEN:      RabbitMQ unavailable, publishes rejected immediately
             Returns ERROR to client (no queue wait)
- HALF_OPEN: After cooldown, allows trial publish
             Success → CLOSED, Failure → OPEN

Benefit: Prevents thread pile-up when RabbitMQ is down
```

### 6.2 Server-side: Channel Pool with auto-recovery
```
- ChannelPool pre-creates N channels on startup
- borrowChannel() / returnChannel() with thread-safe BlockingQueue
- returnChannel() checks isOpen() — broken channels are discarded
  and replaced with fresh channels automatically
- RabbitMQ connection has setAutomaticRecoveryEnabled(true)
  with 5-second retry interval for network failures
```

### 6.3 Consumer-side: basicNack on processing failure
```
- Happy path:  basicAck after successful HTTP broadcast dispatch
- Error path:  basicNack(requeue=false) — message dropped, not requeued
               (prevents poison message loops)
- Async HTTP:  Broadcast failures are logged but do not block consumer
               thread or affect message acknowledgment
```

### 6.4 Client-side: Retry with exponential backoff
```
- Max retries: 5 attempts per message
- Initial backoff: 50ms, doubles each retry
- Connection timeout: 8,000ms
- Failed messages tracked and reported in final metrics
```



## Test Results

### Single Instance Tests

The single-instance baseline test sent 500,000 messages using 40 WebSocket connections (2 per room × 20 rooms) and 128 sender threads. All 500,000 messages were delivered successfully with 0 failures. Main phase throughput was 1,128 msg/sec with a mean response time of 111 ms. RabbitMQ queue depth remained consistently near zero throughout the test, with publish rate at approximately 1,194/s and consumer ack rate matching at 1,193/s, indicating the consumer kept pace with the producer at all times.

---

### Load Balanced Tests

#### Queue Metrics Comparison

| Metric | Single Instance | 2 Instances | 4 Instances |
|--------|----------------|-------------|-------------|
| Throughput (msg/sec) | 1,128 | 1,159 | 1,203 |
| Failed messages | 0 | 0 | 0 |
| Peak queue depth | < 5 | < 5 | < 13 |
| RabbitMQ publish rate | ~1,194/s | ~1,198/s | ~1,233/s |
| Consumer ack rate | ~1,193/s | ~1,197/s | ~1,225/s |
| Mean response time | 111 ms | 108 ms | 104 ms |

Queue depth remained stable and near zero across all configurations, confirming the consumer consistently processed messages without accumulation.

#### Performance Improvement Analysis

Throughput improved from 1,128 msg/sec (single instance) to 1,203 msg/sec (4 instances), a 6.6% improvement. Mean response time decreased from 111 ms to 104 ms as the load was distributed across more server instances.

The modest throughput gain is expected given the test configuration: with 40 client-side WebSocket connections, the client itself is the concurrency bottleneck rather than the servers. Each additional server instance absorbs a share of the incoming connections, reducing per-instance load and slightly improving response time. The ALB distributed connections across 4 healthy targets spanning 2 availability zones (3 in us-east-1d, 1 in us-east-1b), with all targets remaining healthy throughout all test runs.

#### 4-Instance Stress Test (1M Messages)

The 4-instance configuration was further validated with a 1,000,000 message stress test. All 1,000,000 messages were delivered successfully with 0 failures. Throughput held steady at 1,197 msg/sec over a 900-second run, demonstrating system stability under sustained load. Message distribution across all 20 rooms remained even, with each room receiving approximately 50,000 messages. RabbitMQ queue depth stayed near zero throughout, confirming no message accumulation under extended load.


## Configuration Details

### Queue Configuration Parameters

| Parameter | Value |
|-----------|-------|
| Exchange name | chat.exchange |
| Exchange type | topic |
| Exchange durable | true |
| Queue count | 20 (room.1 ~ room.20) |
| Queue type | classic |
| Queue durable | true |
| Routing key pattern | room.{roomId} |
| Message TTL (x-message-ttl) | 3,600,000 ms (1 hour) |
| Max queue length (x-max-length) | 100,000 messages |
| Auto-delete | false |
| Publisher confirms | disabled |
| Message persistence | PERSISTENT_TEXT_PLAIN (delivery mode 2) |

---

### Consumer Configuration

| Parameter | Value |
|-----------|-------|
| Consumer thread count | 20 |
| Prefetch count (basicQos) | 200 |
| Queue assignment strategy | Round-robin |
| Acknowledgment mode | Manual (autoAck = false) |
| ACK timing | Before HTTP broadcast dispatch |
| Nack on failure | requeue = false |
| Deduplication | ConcurrentHashSet, cleared at 10,000 entries |
| Broadcast method | HTTP POST /internal/broadcast (async) |
| HTTP client timeout | 3 seconds |
| Auto-recovery | Enabled, 5-second retry interval |

---

### ALB Settings

| Parameter | Value |
|-----------|-------|
| Load balancer type | Application Load Balancer (ALB) |
| Scheme | Internet-facing |
| Protocol | HTTP / WebSocket |
| Port | 8080 |
| Availability Zones | us-east-1b, us-east-1d |
| Sticky sessions | Enabled (1 day duration) |
| Health check path | /health |
| Health check protocol | HTTP |
| Health check interval | 30 seconds |
| Health check timeout | 5 seconds |
| Healthy threshold | 2 consecutive successes |
| Unhealthy threshold | 3 consecutive failures |
| Success codes | 200 |

---

### Instance Types Used

| Component | Instance Type | Count |
|-----------|--------------|-------|
| Server (server-v2) | t3.micro | 4 |
| RabbitMQ | t2.micro | 1 |
| Consumer | t2.micro | 1 |


## Performance Tuning Experiments with 2 instance(500k message) 
### Experiment 1: Client Thread Count

| Client Threads | Throughput | Failed | Mean RT | Peak Queue Depth | Publish/Ack Gap | Conclusion                    |
| -------------- | ---------: | -----: | ------: | ---------------: | --------------: | ----------------------------- |
| 128            | 1090msg/sec   |   0    |     120ms    |        15        |    64/s         | improved                      |
| 200            |     1203msg/sec       |    0    |    104ms     |        13        |    20/s         | best balance                  |
| 256            |     1250msg/sec       |    0    |    98ms     |         50         |       55/s          | marginal gain / more overhead |
| 512            |      1300msg/sec      |      0  |   94      |        420        |       120/s         | diminishing returns           |


Experiment 2: Consumer Concurrency
| Consumer Threads | Throughput | Failed | Peak Queue Depth | Ack Rate | Mean RT |Conclusion          |
| ---------------- | ---------: | -----: | ---------------: | -------: | ------:  |------------------- |
| 20               |    1080msg/sec        |   0     |     6             |  1130/s        |    125ms    |improved            |
| 40               |   1203msg/sec         |   0     |      13            |    1233/s     |     105ms    |best balance        |
| 80               |     1805msg/sec       |    0    |       360          |    1970/s      |    120ms      |limited benefit     |


Experiment 3: Prefetch Count
| Prefetch | Throughput (msg/s) | Ack Rate (/s) | Peak Queue Depth | Mean RT (ms) |Conclusion          |
| -------- | -----------------: | ------------: | ---------------: | -----------: |------------------- |
| 50       |         980msg/sec            |     930/s           |        5          |       135ms       | consumer bottleneck |
| 100      |          1090msg/sec           |       1033/s           |      13            |      120ms        |improved            |
| 200      |       1203msg/sec               |     1233/s          |         13         |        105ms      |best balance        |
| 500      |         2010msg/sec           |        1920/s       |          395       |      88ms        |limited benefit     |

The experiments show that increasing client threads, consumer concurrency, and prefetch count initially improves system throughput. However, beyond certain thresholds, the performance gains become marginal while queue depth and system overhead increase.

For client concurrency, throughput improves up to around 200 threads, after which additional threads only provide small improvements but lead to larger queue backlogs.

For consumer concurrency, increasing threads from 20 to 40 significantly improves throughput and reduces latency. Increasing to 80 threads results in limited benefit and higher queue accumulation due to thread contention.

For prefetch count, performance improves as the value increases to 200, which balances message throughput and queue stability. A prefetch of 500 increases throughput but causes significant queue buildup.

Overall, the best system balance is achieved with 200 client threads, 40 consumer threads, and a prefetch count of 200, which provides stable throughput, low latency, and minimal queue accumulation.