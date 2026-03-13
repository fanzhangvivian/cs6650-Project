package com.chatflow.service;

import com.chatflow.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts messages from the queue to all WebSocket sessions in a room.
 * Handles per-session failures gracefully without affecting other sessions.
 * Ack decisions are made by MessageConsumerService, not here.
 */
@Service
public class RoomBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(RoomBroadcaster.class);

    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    // -------------------------
    // Metrics
    // -------------------------

    private final AtomicLong totalDelivered = new AtomicLong(0);
    private final AtomicLong totalFailed    = new AtomicLong(0);

    public RoomBroadcaster(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    // -------------------------
    // Broadcast
    // -------------------------

    /**
     * Broadcasts a QueueMessage to all active WebSocket sessions in the room.
     *
     * Single session failures are logged and skipped - they do not affect
     * other sessions or the ack decision in MessageConsumerService.
     *
     * @param queueMessage the message to broadcast
     * @return BroadcastResult containing delivered and failed counts
     */
    public BroadcastResult broadcast(QueueMessage queueMessage) {
        String roomId = queueMessage.getRoomId();
        ConcurrentHashMap<String, WebSocketSession> sessions = connectionManager.getRoomSessions(roomId);

        if (sessions == null || sessions.isEmpty()) {
            logger.debug("No active sessions in room {}, message {} discarded",
                roomId, queueMessage.getMessageId());
            return new BroadcastResult(0, 0);
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(buildPayload(queueMessage));
        } catch (Exception e) {
            logger.error("Failed to serialize broadcast payload for message {}",
                queueMessage.getMessageId(), e);
            return new BroadcastResult(0, sessions.size());
        }

        int delivered = 0;
        int failed    = 0;

        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                // Session already closed - remove it and skip
                connectionManager.removeSession(roomId, session);
                logger.debug("Removed closed session {} from room {}", session.getId(), roomId);
                continue;
            }

            try {
                session.sendMessage(new TextMessage(jsonPayload));
                delivered++;
                totalDelivered.incrementAndGet();
            } catch (Exception e) {
                // Single session failure - log and continue broadcasting to others
                failed++;
                totalFailed.incrementAndGet();
                logger.warn("Failed to send to session {} in room {}: {}",
                    session.getId(), roomId, e.getMessage());
            }
        }

        logger.debug("Broadcast complete | Room: {} | Delivered: {} | Failed: {}",
            roomId, delivered, failed);

        return new BroadcastResult(delivered, failed);
    }

    // -------------------------
    // Payload Builder
    // -------------------------

    /**
     * Builds a client-friendly payload map from a QueueMessage.
     * Only exposes fields relevant to the client.
     *
     * @param queueMessage the source message from the queue
     * @return map representing the JSON payload to send to clients
     */
    private Map<String, Object> buildPayload(QueueMessage queueMessage) {
        return Map.of(
            "messageId",   queueMessage.getMessageId(),
            "roomId",      queueMessage.getRoomId(),
            "userId",      queueMessage.getUserId(),
            "username",    queueMessage.getUsername(),
            "message",     queueMessage.getMessage(),
            "timestamp",   queueMessage.getTimestamp(),
            "messageType", queueMessage.getMessageType(),
            "serverId",    queueMessage.getServerId()
        );
    }

    // -------------------------
    // Metrics
    // -------------------------

    /**
     * Returns the total number of successfully delivered messages across all sessions.
     *
     * @return total delivered count
     */
    public long getTotalDelivered() {
        return totalDelivered.get();
    }

    /**
     * Returns the total number of failed session deliveries.
     *
     * @return total failed count
     */
    public long getTotalFailed() {
        return totalFailed.get();
    }

    // -------------------------
    // BroadcastResult
    // -------------------------

    /**
     * Immutable result of a broadcast operation.
     * Used by MessageConsumerService for logging only - not for ack decisions.
     */
    public static class BroadcastResult {
        private final int delivered;
        private final int failed;

        public BroadcastResult(int delivered, int failed) {
            this.delivered = delivered;
            this.failed    = failed;
        }

        public int getDelivered() { return delivered; }
        public int getFailed()    { return failed; }
    }
}