package com.chatflow.consumer.service;

import com.chatflow.consumer.model.MessageEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory statistics aggregator for consumer-v3 internal monitoring.
 *
 * PURPOSE:
 *   Tracks DB write throughput in real time and logs it every 10 seconds.
 *   This log output is used to extract time-series data for Part 4
 *   Performance Report (write throughput graphs).
 *
 * IMPORTANT - Data source boundary:
 *   This class is for INTERNAL MONITORING ONLY.
 *   Metrics API (GET /metrics on server-v2) reads exclusively from PostgreSQL.
 *   StatisticsAggregator data is NEVER exposed via any HTTP endpoint.
 *
 * When record() is called:
 *   - Only after confirmed successful DB write (saveAll or upsert with result=1)
 *   - Called by DatabaseWriterService after write success
 *   - Malformed messages, DLQ messages, and duplicates are NOT recorded
 */
@Service
public class StatisticsAggregator {

    private static final Logger logger =
            LoggerFactory.getLogger(StatisticsAggregator.class);

    private static final int LOG_INTERVAL_SECONDS = 10;

    // Total messages successfully written to DB since consumer started
    private final AtomicLong totalWritten = new AtomicLong(0);

    // Messages written in the current 10-second window
    private final AtomicLong windowCount = new AtomicLong(0);

    // Window start time for rate calculation
    private volatile long windowStartMs = System.currentTimeMillis();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void startLogging() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-aggregator");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::logThroughput,
                LOG_INTERVAL_SECONDS,
                LOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        logger.info("StatisticsAggregator started, logging every {}s", LOG_INTERVAL_SECONDS);
    }

    /**
     * Records a successfully persisted message.
     *
     * Called by DatabaseWriterService ONLY after confirmed DB write:
     *   - After saveAll(batch) succeeds: called for each message in batch
     *   - After upsert() returns 1 (actual insert, not duplicate)
     *
     * NOT called for:
     *   - Messages that went to DLQ
     *   - Duplicate messages (upsert returned 0)
     *   - Malformed messages
     *
     * @param entity the successfully persisted MessageEntity
     */
    public void record(MessageEntity entity) {
        totalWritten.incrementAndGet();
        windowCount.incrementAndGet();
    }

    /**
     * Logs current DB write throughput.
     * Called every 10 seconds by the scheduler.
     *
     * Log format (used for Part 4 time-series extraction):
     *   [Stats] DB write throughput: XXXX/s | Total written: XXXXXX
     *
     * Extract from consumer log with:
     *   grep "\[Stats\]" chatflow-consumer-v3.log
     */
    private void logThroughput() {
        long now       = System.currentTimeMillis();
        long windowMs  = now - windowStartMs;
        long count     = windowCount.getAndSet(0);
        windowStartMs  = now;

        double ratePerSec = windowMs > 0
                ? count / (windowMs / 1000.0)
                : 0;

        logger.info("[Stats] DB write throughput: {}/s | Total written: {} | Window: {}ms",
                String.format("%.0f", ratePerSec),
                totalWritten.get(),
                windowMs);
    }

    /**
     * Returns total messages successfully written to DB.
     * Used by HealthController for /health endpoint metrics.
     */
    public long getTotalWritten() {
        return totalWritten.get();
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        // Log final stats on shutdown
        logThroughput();
        logger.info("StatisticsAggregator shutdown. Final total written: {}",
                totalWritten.get());
    }
}