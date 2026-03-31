package com.chatflow.consumer.service;

import com.chatflow.consumer.model.MessageEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 *                                      (true JDBC batch INSERT via JdbcTemplate)
 *
 * Key design decisions:
 *   1. JdbcTemplate.batchUpdate() bypasses Hibernate IDENTITY restriction:
 *      saveAll() with IDENTITY degrades to N single INSERTs (Hibernate must
 *      read back each generated id). JdbcTemplate sends all N rows in one
 *      JDBC batch — one network round-trip regardless of batch size.
 *   2. ON CONFLICT (message_id) DO NOTHING in the INSERT SQL:
 *      duplicates (rare nack-requeue) are silently skipped at DB level,
 *      no DataIntegrityViolationException, no per-row upsert fallback needed.
 *   3. Decoupled from RabbitMQ consumer: DB write never blocks consumption.
 *   4. Bounded buffer (batchSize × 10): prevents OOM under high load.
 *   5. Two flush triggers: timer (every flushIntervalMs) OR buffer full.
 *   6. Both triggers go through flushing guard: prevents flush storm.
 *   7. statisticsAggregator.record() called AFTER successful write only.
 *   8. Exponential backoff retry + DLQ on persistent failure.
 *   9. DB Circuit Breaker: stops writes when DB is unhealthy.
 */
@Service
public class DatabaseWriterService {

    private static final Logger logger =
            LoggerFactory.getLogger(DatabaseWriterService.class);

    // True JDBC batch INSERT — ON CONFLICT skips duplicates silently.
    // No Hibernate involvement: bypasses IDENTITY-disables-batching restriction.
    private static final String INSERT_SQL = """
            INSERT INTO messages (
                message_id, room_id, user_id, username, message,
                event_time, message_type, server_id, client_ip,
                published_at, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StatisticsAggregator statisticsAggregator;
    private final DbCircuitBreaker dbCircuitBreaker;

    @Value("${database.batch-size:1000}")
    private int batchSize;

    @Value("${database.flush-interval-ms:200}")
    private long flushIntervalMs;

    @Value("${database.writer-thread-count:20}")
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

    // Write latency samples for p50/p95/p99
    private final ConcurrentLinkedQueue<Long> writeLatencies =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_LATENCY_SAMPLES = 10000;

    public DatabaseWriterService(JdbcTemplate jdbcTemplate,
                                 StatisticsAggregator statisticsAggregator,
                                 DbCircuitBreaker dbCircuitBreaker) {
        this.jdbcTemplate         = jdbcTemplate;
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
     */
    public boolean offer(MessageEntity entity) {
        boolean accepted = buffer.offer(entity);

        if (accepted) {
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
     * CAS on flushing ensures only one flush task is submitted at a time.
     */
    private void triggerFlush() {
        if (flushing.compareAndSet(false, true)) {
            writerExecutor.submit(this::flushBuffer);
        }
    }

    /**
     * Drains up to batchSize messages from buffer and writes to PostgreSQL
     * via true JDBC batch INSERT (JdbcTemplate.batchUpdate).
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
            flushing.set(false);
        }
    }

    /**
     * Writes a batch to PostgreSQL using JdbcTemplate.batchUpdate().
     *
     * Happy path:
     *   jdbcTemplate.batchUpdate() → JDBC batch INSERT (one round-trip)
     *   ON CONFLICT DO NOTHING handles duplicates silently at DB level
     *   → record stats → done
     *
     * Failure path (DB unavailable):
     *   Exponential backoff retry (1s → 2s → 4s) → DLQ
     */
    private void flushWithRetry(List<MessageEntity> batch) {
        if (!dbCircuitBreaker.isAllowed()) {
            logger.warn("DB CircuitBreaker OPEN, sending batch of {} to DLQ", batch.size());
            sendToDlq(batch);
            return;
        }

        try {
            int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    MessageEntity e = batch.get(i);
                    ps.setString(   1, e.getMessageId());
                    ps.setString(   2, e.getRoomId());
                    ps.setString(   3, e.getUserId());
                    ps.setString(   4, e.getUsername());
                    ps.setString(   5, e.getMessage());
                    ps.setTimestamp(6, e.getEventTime()   != null ? Timestamp.from(e.getEventTime())   : null);
                    ps.setString(   7, e.getMessageType());
                    ps.setString(   8, e.getServerId());
                    ps.setString(   9, e.getClientIp());
                    ps.setTimestamp(10, e.getPublishedAt() != null ? Timestamp.from(e.getPublishedAt()) : null);
                    ps.setTimestamp(11, Timestamp.from(e.getReceivedAt()));
                }

                @Override
                public int getBatchSize() { return batch.size(); }
            });

            dbCircuitBreaker.recordSuccess();

            // ON CONFLICT DO NOTHING: result[i] == 0 means duplicate skipped
            int written = 0;
            int dupes   = 0;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > 0) {
                    written++;
                    statisticsAggregator.record(batch.get(i));
                } else {
                    dupes++;
                }
            }
            totalWritten.addAndGet(written);
            totalDupes.addAndGet(dupes);

            if (dupes > 0) {
                logger.info("Batch written: {} inserted, {} duplicates skipped (ON CONFLICT)",
                        written, dupes);
            } else {
                logger.debug("Batch written: {} messages", written);
            }

        } catch (Exception e) {
            logger.error("Batch write failed (size={}), starting retry", batch.size(), e);
            dbCircuitBreaker.recordFailure();
            retryWithBackoff(batch);
        }
    }

    /**
     * Exponential backoff retry: 1s → 2s → 4s, then DLQ.
     */
    private void retryWithBackoff(List<MessageEntity> batch) {
        int maxRetries = 3;
        long backoffMs = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (!dbCircuitBreaker.isAllowed()) {
                logger.warn("CircuitBreaker OPEN during retry attempt {}, sending to DLQ", attempt);
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
                jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        MessageEntity e = batch.get(i);
                        ps.setString(   1, e.getMessageId());
                        ps.setString(   2, e.getRoomId());
                        ps.setString(   3, e.getUserId());
                        ps.setString(   4, e.getUsername());
                        ps.setString(   5, e.getMessage());
                        ps.setTimestamp(6, e.getEventTime()   != null ? Timestamp.from(e.getEventTime())   : null);
                        ps.setString(   7, e.getMessageType());
                        ps.setString(   8, e.getServerId());
                        ps.setString(   9, e.getClientIp());
                        ps.setTimestamp(10, e.getPublishedAt() != null ? Timestamp.from(e.getPublishedAt()) : null);
                        ps.setTimestamp(11, Timestamp.from(e.getReceivedAt()));
                    }

                    @Override
                    public int getBatchSize() { return batch.size(); }
                });

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

    private void sendToDlq(List<MessageEntity> batch) {
        totalFailed.addAndGet(batch.size());
        logger.error("DLQ: {} messages could not be persisted after all retries", batch.size());
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
