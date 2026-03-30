package com.chatflow.consumer.controller;

import com.chatflow.consumer.service.DatabaseWriterService;
import com.chatflow.consumer.service.DbCircuitBreaker;
import com.chatflow.consumer.service.MessageConsumerService;
import com.chatflow.consumer.service.ServerHttpClient;
import com.chatflow.consumer.service.StatisticsAggregator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Health check endpoint for the Consumer application (Assignment 3).
 *
 * Exposes:
 *   - Consumer metrics (broadcast, ack, malformed)
 *   - DB writer metrics (written, failed, dupes, buffer size)
 *   - DB circuit breaker state
 *   - Write latency percentiles (p50, p95, p99)
 *   - Statistics aggregator total
 */
@RestController
public class HealthController {

    private final MessageConsumerService messageConsumerService;
    private final ServerHttpClient serverHttpClient;
    private final DatabaseWriterService databaseWriterService;
    private final DbCircuitBreaker dbCircuitBreaker;
    private final StatisticsAggregator statisticsAggregator;
    private final Instant startTime;

    public HealthController(MessageConsumerService messageConsumerService,
                            ServerHttpClient serverHttpClient,
                            DatabaseWriterService databaseWriterService,
                            DbCircuitBreaker dbCircuitBreaker,
                            StatisticsAggregator statisticsAggregator) {
        this.messageConsumerService = messageConsumerService;
        this.serverHttpClient       = serverHttpClient;
        this.databaseWriterService  = databaseWriterService;
        this.dbCircuitBreaker       = dbCircuitBreaker;
        this.statisticsAggregator   = statisticsAggregator;
        this.startTime              = Instant.now();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();

        // ── Service status ────────────────────────────────────────────────────
        response.put("status", "UP");
        response.put("service", "ChatFlow Consumer v3");
        response.put("timestamp", Instant.now().toString());
        response.put("startTime", startTime.toString());
        response.put("uptime", calculateUptime());

        // ── Consumer metrics ──────────────────────────────────────────────────
        response.put("messagesConsumed",   messageConsumerService.getMessagesConsumed());
        response.put("messagesFailed",     messageConsumerService.getMessagesFailed());
        response.put("messagesBuffered",   messageConsumerService.getMessagesBuffered());
        response.put("bufferFullNacks",    messageConsumerService.getBufferFullNacks());
        response.put("malformedMessages",  messageConsumerService.getMalformedMessages());
        response.put("activeConsumers",    messageConsumerService.getActiveConsumerCount());

        // ── Broadcast metrics ─────────────────────────────────────────────────
        response.put("broadcastSuccess",   serverHttpClient.getBroadcastSuccess());
        response.put("broadcastFailed",    serverHttpClient.getBroadcastFailed());

        // ── DB writer metrics ─────────────────────────────────────────────────
        response.put("dbWritten",          databaseWriterService.getTotalWritten());
        response.put("dbFailed",           databaseWriterService.getTotalFailed());
        response.put("dbDupes",            databaseWriterService.getTotalDupes());
        response.put("dbBufferSize",       databaseWriterService.getBufferSize());

        // ── DB circuit breaker ────────────────────────────────────────────────
        response.put("dbCircuitBreakerState",
                dbCircuitBreaker.getState().name());
        response.put("dbCircuitBreakerFailures",
                dbCircuitBreaker.getFailureCount());

        // ── Write latency percentiles (p50 / p95 / p99) ───────────────────────
        // Calculated from DatabaseWriterService latency samples
        // Used for Part 4 Performance Report
        response.put("writeLatencyMs", calculateLatencyPercentiles());

        // ── Statistics aggregator ─────────────────────────────────────────────
        // Internal monitoring only — does NOT feed Metrics API
        response.put("statsAggregatorTotal", statisticsAggregator.getTotalWritten());

        return response;
    }

    /**
     * Calculates p50, p95, p99 write latency from DatabaseWriterService samples.
     * Returns a map with keys: p50, p95, p99, min, max, sampleCount.
     */
    private Map<String, Object> calculateLatencyPercentiles() {
        List<Long> latencies = databaseWriterService.getWriteLatencies();
        Map<String, Object> percentiles = new HashMap<>();

        if (latencies.isEmpty()) {
            percentiles.put("p50", 0);
            percentiles.put("p95", 0);
            percentiles.put("p99", 0);
            percentiles.put("min", 0);
            percentiles.put("max", 0);
            percentiles.put("sampleCount", 0);
            return percentiles;
        }

        // latencies is already sorted (sorted in DatabaseWriterService.getWriteLatencies())
        int size = latencies.size();
        percentiles.put("p50",  latencies.get((int) (size * 0.50)));
        percentiles.put("p95",  latencies.get((int) (size * 0.95)));
        percentiles.put("p99",  latencies.get((int) (size * 0.99)));
        percentiles.put("min",  latencies.get(0));
        percentiles.put("max",  latencies.get(size - 1));
        percentiles.put("sampleCount", size);

        return percentiles;
    }

    private String calculateUptime() {
        long uptimeSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        long hours   = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}