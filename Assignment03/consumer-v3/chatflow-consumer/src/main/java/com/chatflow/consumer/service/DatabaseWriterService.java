package com.chatflow.consumer.service;

import com.chatflow.consumer.model.MessageEntity;
import com.chatflow.consumer.repository.MessageRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write-Behind database writer service for Assignment 3.
 *
 * Architecture:
 *   consumer workers → offer(entity) → LinkedBlockingQueue
 *                                            ↓
 *                                      DB writer threads
 *                                      (batch INSERT PostgreSQL)
 *
 * Key design decisions:
 *   1. Decoupled from RabbitMQ consumer: DB write never blocks consumption
 *   2. Bounded buffer (batchSize × 10): prevents OOM under high load
 *   3. Two flush triggers: timer (every flushIntervalMs) OR buffer full
 *   4. Both triggers go through flushing guard: prevents flush storm
 *   5. statisticsAggregator.record() called AFTER successful write only
 *   6. upsert() returns int: accurate totalWritten vs totalDupes tracking
 *   7. Exponential backoff retry + DLQ on persistent failure
 *   8. DB Circuit Breaker: stops writes when DB is unhealthy
 */
@Service
public class DatabaseWriterService {

    private static final Logger logger =
            LoggerFactory.getLogger(DatabaseWriterService.class);

    private final MessageRepository messageRepository;
    private final StatisticsAggregator statisticsAggregator;
    private final DbCircuitBreaker dbCircuitBreaker;

    @Value("${database.batch-size:1000}")
    private int batchSize;

    @Value("${database.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${database.writer-thread-count:10}")
    private int writerThreadCount;

    // Bounded in-memory buffer between consumer workers and DB writers
    private LinkedBlockingQueue<MessageEntity> buffer;

    // Thread pool for batch DB writes
    private ExecutorService writerExecutor;

    // Single-thread scheduler for time-based flush trigger
    private ScheduledExecutorService flushScheduler;

    // Prevents flush storm: both size-triggered and timer-triggered flushes
    // go through this guard. Only one flush task runs at a time.
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    // Metrics
    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalFailed  = new AtomicLong(0);
    private final AtomicLong totalDupes   = new AtomicLong(0);

    // Write latency samples for p50/p95/p99 in Part 4 Performance Report
    private final ConcurrentLinkedQueue<Long> writeLatencies =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_LATENCY_SAMPLES = 10000;

    public DatabaseWriterService(MessageRepository messageRepository,
                                 StatisticsAggregator statisticsAggregator,
                                 DbCircuitBreaker dbCircuitBreaker) {
        this.messageRepository    = messageRepository;
        this.statisticsAggregator = statisticsAggregator;
        this.dbCircuitBreaker     = dbCircuitBreaker;
    }

    @PostConstruct
    public void init() {
        buffer         = new LinkedBlockingQueue<>(batchSize * 10);
        writerExecutor = Executors.newFixedThreadPool(writerThreadCount);

        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-flush-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Timer-triggered flush also goes through flushing guard.
        // Prevents overlap between timer flush and size-triggered flush.
        flushScheduler.scheduleAtFixedRate(
                this::triggerFlush,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
        );

        logger.info("DatabaseWriterService initialized: batchSize={}, " +
                        "flushInterval={}ms, writerThreads={}, bufferCapacity={}",
                batchSize, flushIntervalMs, writerThreadCount, batchSize * 10);
    }

    /**
     * Offers a MessageEntity to the in-memory buffer.
     * Called by MessageConsumerService after broadcast succeeds.
     *
     * Non-blocking: returns false immediately if buffer is full.
     * Caller nacks the RabbitMQ message on false → requeue, no data loss.
     *
     * NOTE: statisticsAggregator.record() is NOT called here.
     * It is called only after confirmed successful DB write,
     * so aggregator stats reflect actual persistence, not buffer entry.
     */
    public boolean offer(MessageEntity entity) {
        boolean accepted = buffer.offer(entity);

        if (accepted) {
            // Size-based flush trigger — goes through same flushing guard
            if (buffer.size() >= batchSize) {
                triggerFlush();
            }
        } else {
            logger.warn("DB buffer full (capacity={}), message dropped. " +
                    "Consumer will nack for requeue.", batchSize * 10);
        }

        return accepted;
    }

    /**
     * Central flush trigger used by BOTH size-triggered and timer-triggered paths.
     * CAS on flushing ensures only one flush task is submitted at a time,
     * preventing flush storm regardless of which trigger fires.
     */
    private void triggerFlush() {
        if (flushing.compareAndSet(false, true)) {
            writerExecutor.submit(this::flushBuffer);
        }
    }

    /**
     * Drains up to batchSize messages from buffer and writes to PostgreSQL.
     * drainTo() is atomic: concurrent calls each get a distinct batch.
     */
    private void flushBuffer() {
        try {
            if (buffer.isEmpty()) return;

            List<MessageEntity> batch = new ArrayList<>(batchSize);
            buffer.drainTo(batch, batchSize);
            if (batch.isEmpty()) return;

            long startTime = System.currentTimeMillis();
            flushWithRetry(batch);
            long latencyMs = System.currentTimeMillis() - startTime;

            writeLatencies.offer(latencyMs);
            while (writeLatencies.size() > MAX_LATENCY_SAMPLES) {
                writeLatencies.poll();
            }

        } finally {
            // Always release flag so next trigger can submit a new flush
            flushing.set(false);
        }
    }

    /**
     * Attempts to write a batch to PostgreSQL.
     *
     * Happy path (>>99% of cases):
     *   saveAll(batch) → JDBC batch INSERT → record stats → done
     *
     * Duplicate path (rare: consumer restart or nack-requeue):
     *   DataIntegrityViolationException → per-row upsert
     *   upsert() returns 1 (inserted) or 0 (duplicate skipped)
     *
     * Failure path (DB unavailable):
     *   Exponential backoff retry (1s → 2s → 4s) → DLQ
     */
    private void flushWithRetry(List<MessageEntity> batch) {
        if (!dbCircuitBreaker.isAllowed()) {
            logger.warn("DB CircuitBreaker OPEN, sending batch of {} to DLQ",
                    batch.size());
            sendToDlq(batch);
            return;
        }

        try {
            messageRepository.saveAll(batch);
            dbCircuitBreaker.recordSuccess();

            // Record stats AFTER confirmed write — not at buffer entry
            totalWritten.addAndGet(batch.size());
            batch.forEach(statisticsAggregator::record);

            logger.debug("Batch written: {} messages", batch.size());

        } catch (DataIntegrityViolationException e) {
            logger.warn("Duplicate message_id in batch of {}, falling back to upsert",
                    batch.size());
            handleDuplicates(batch);

        } catch (Exception e) {
            logger.error("Batch write failed (size={}), starting retry",
                    batch.size(), e);
            dbCircuitBreaker.recordFailure();
            retryWithBackoff(batch);
        }
    }

    /**
     * Per-row upsert fallback for batches containing duplicate message_ids.
     *
     * upsert() returns:
     *   1 → row actually inserted → count as written, record in aggregator
     *   0 → duplicate skipped    → count as dupe, do NOT record in aggregator
     *
     * This gives accurate totalWritten and totalDupes without guessing.
     */
    private void handleDuplicates(List<MessageEntity> batch) {
        int written = 0;
        int dupes   = 0;

        for (MessageEntity entity : batch) {
            try {
                int result = messageRepository.upsert(entity);
                if (result == 1) {
                    written++;
                    statisticsAggregator.record(entity); // only on actual insert
                } else {
                    dupes++;
                    logger.debug("Duplicate skipped: messageId={}", entity.getMessageId());
                }
            } catch (Exception ex) {
                logger.error("Upsert failed for messageId={}",
                        entity.getMessageId(), ex);
                totalFailed.incrementAndGet();
            }
        }

        totalWritten.addAndGet(written);
        totalDupes.addAndGet(dupes);
        logger.info("Upsert complete: {} inserted, {} duplicates skipped",
                written, dupes);
    }

    /**
     * Exponential backoff retry: 1s → 2s → 4s, then DLQ.
     */
    private void retryWithBackoff(List<MessageEntity> batch) {
        int maxRetries = 3;
        long backoffMs = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (!dbCircuitBreaker.isAllowed()) {
                logger.warn("CircuitBreaker OPEN during retry attempt {}, sending to DLQ",
                        attempt);
                sendToDlq(batch);
                return;
            }

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendToDlq(batch);
                return;
            }

            try {
                messageRepository.saveAll(batch);
                dbCircuitBreaker.recordSuccess();
                totalWritten.addAndGet(batch.size());
                batch.forEach(statisticsAggregator::record);
                logger.info("Batch write succeeded on retry {}/{}", attempt, maxRetries);
                return;
            } catch (Exception e) {
                dbCircuitBreaker.recordFailure();
                logger.warn("Retry {}/{} failed, next backoff={}ms",
                        attempt, maxRetries, backoffMs * 2, e);
                backoffMs *= 2;
            }
        }

        logger.error("All {} retries exhausted for batch of {}, sending to DLQ",
                maxRetries, batch.size());
        sendToDlq(batch);
    }

    /**
     * Dead Letter Queue handler for messages that could not be persisted.
     *
     * Current implementation: logs messageIds for auditability.
     * TODO: publish to RabbitMQ chat.dlq queue (to be completed in Step 2.5
     * when RabbitMQ channel is available via MessageConsumerService).
     */
    private void sendToDlq(List<MessageEntity> batch) {
        totalFailed.addAndGet(batch.size());
        logger.error("DLQ: {} messages could not be persisted after all retries",
                batch.size());
        batch.stream()
                .limit(5)
                .forEach(e -> logger.error("DLQ messageId: {}", e.getMessageId()));
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public long getTotalWritten() { return totalWritten.get(); }
    public long getTotalFailed()  { return totalFailed.get(); }
    public long getTotalDupes()   { return totalDupes.get(); }
    public int  getBufferSize()   { return buffer != null ? buffer.size() : 0; }

    public List<Long> getWriteLatencies() {
        List<Long> snapshot = new ArrayList<>(writeLatencies);
        Collections.sort(snapshot);
        return snapshot;
    }

    @PreDestroy
    public void shutdown() {
        logger.info("DatabaseWriterService shutting down, flushing remaining buffer...");

        flushScheduler.shutdown();

        if (buffer != null && !buffer.isEmpty()) {
            List<MessageEntity> remaining = new ArrayList<>();
            buffer.drainTo(remaining);
            if (!remaining.isEmpty()) {
                logger.info("Flushing {} remaining messages on shutdown",
                        remaining.size());
                flushWithRetry(remaining);
            }
        }

        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
                logger.warn("DatabaseWriterService forced shutdown after 30s");
            }
        } catch (InterruptedException e) {
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("DatabaseWriterService shutdown complete. " +
                        "Written={}, Failed={}, Dupes={}",
                totalWritten.get(), totalFailed.get(), totalDupes.get());
    }
}