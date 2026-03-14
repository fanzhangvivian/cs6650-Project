package com.chatflow.client.sender;

import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.metrics.DetailedMetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detailed Message Sender - measures RTT latency for 100% of messages.
 *
 * Each message is sent synchronously via sendAndAwaitAck(), waiting for
 * the server's echo/ack before recording latency and moving to the next message.
 *
 * Retry policy: up to MAX_RETRIES (5) attempts with exponential backoff.
 * This satisfies Assignment requirements:
 *   - Retry failed sends up to 5 times with exponential backoff
 *   - Accurate latency measurement for every message
 */
public class DetailedMessageSender implements Runnable {

    private final MessageQueue messageQueue;
    private final DetailedMetricsCollector metricsCollector;
    private final ConnectionPool connectionPool;
    private final int messagesToSend;
    private final ObjectMapper objectMapper;

    // RTT timeout per message (ms)
    private static final long ACK_TIMEOUT_MS = 5000;

    // Round-robin slot counter per room
    private final ConcurrentHashMap<String, AtomicInteger> roomCounters = new ConcurrentHashMap<>();

    public DetailedMessageSender(MessageQueue messageQueue,
                                 DetailedMetricsCollector metricsCollector,
                                 ConnectionPool connectionPool,
                                 int messagesToSend) {
        this.messageQueue = messageQueue;
        this.metricsCollector = metricsCollector;
        this.connectionPool = connectionPool;
        this.messagesToSend = messagesToSend;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void run() {
        int sent = 0;
        int failed = 0;

        try {
            for (int i = 0; i < messagesToSend; i++) {
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message == null) {
                    System.err.println("  ⚠️  Thread timeout waiting for message");
                    break;
                }

                boolean success = sendMessageWithRtt(message);
                if (success) sent++;
                else failed++;
            }

        } catch (Exception e) {
            System.err.println("  ❌ Thread error: " + e.getMessage());
        }

        System.out.println("  ✅ Thread " + Thread.currentThread().getName() +
                " completed: sent=" + sent + ", failed=" + failed);
    }

    /**
     * Send a single message with RTT measurement.
     * Retries up to MAX_RETRIES (5) times with exponential backoff.
     *
     * Backoff sequence: 50ms -> 100ms -> 200ms -> 400ms -> 800ms
     */
    private boolean sendMessageWithRtt(ChatMessage message) {
        long sendTimestamp = System.currentTimeMillis();

        // Generate messageId once before retries so server ack can be correlated
        if (message.getMessageId() == null || message.getMessageId().isEmpty()) {
            message.setMessageId(ChatWebSocketClient.newMessageId());
        }

        long delay = ClientConfig.INITIAL_BACKOFF_MS; // 50ms

        for (int attempt = 0; attempt < ClientConfig.MAX_RETRIES; attempt++) {
            int slot = Math.abs(
                    roomCounters
                        .computeIfAbsent(message.getRoomId(), r -> new AtomicInteger(0))
                        .getAndIncrement()
            ) % ClientConfig.CONNECTIONS_PER_ROOM;

            try {
                ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId(), slot);

                if (!client.isConnected()) {
                    connectionPool.removeConnection(message.getRoomId(), slot);
                    sleepBackoff(delay);
                    delay *= 2;
                    continue;
                }

                String jsonMessage = objectMapper.writeValueAsString(message);
                ChatWebSocketClient.AckResult ack = client.sendAndAwaitAck(
                        jsonMessage,
                        message.getMessageId(),
                        ACK_TIMEOUT_MS
                );

                metricsCollector.recordMessage(
                        sendTimestamp,
                        message.getMessageType(),
                        ack.latencyMs,
                        ack.success ? 200 : 400,
                        message.getRoomId()
                );

                return ack.success;

            } catch (Exception e) {
                connectionPool.removeConnection(message.getRoomId(), slot);

                if (attempt == ClientConfig.MAX_RETRIES - 1) {
                    // All retries exhausted - record as failure
                    metricsCollector.recordMessage(
                            sendTimestamp,
                            message.getMessageType(),
                            ACK_TIMEOUT_MS,
                            500,
                            message.getRoomId()
                    );
                    metricsCollector.incrementReconnections();
                    return false;
                }

                // Exponential backoff before next retry
                sleepBackoff(delay);
                delay *= 2;
            }
        }
        return false;
    }

    /**
     * Sleep for backoff duration, handling interruption gracefully.
     *
     * @param ms milliseconds to sleep
     */
    private void sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}