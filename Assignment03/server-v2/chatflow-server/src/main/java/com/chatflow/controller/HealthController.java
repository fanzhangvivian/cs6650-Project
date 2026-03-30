package com.chatflow.controller;

import com.chatflow.service.ChannelPool;
import com.chatflow.service.CircuitBreaker;
import com.chatflow.service.ConnectionManager;
import com.chatflow.service.MessagePublisher;
import com.chatflow.service.RoomBroadcaster;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint exposing server status and runtime metrics.
 * Includes RabbitMQ publish statistics, circuit breaker state,
 * channel pool availability, and broadcast metrics for monitoring purposes.
 */
@RestController
public class HealthController {

    private final ConnectionManager connectionManager;
    private final MessagePublisher messagePublisher;
    private final CircuitBreaker circuitBreaker;
    private final ChannelPool channelPool;
    private final RoomBroadcaster roomBroadcaster;
    private final Instant startTime;

    public HealthController(ConnectionManager connectionManager,
                            MessagePublisher messagePublisher,
                            CircuitBreaker circuitBreaker,
                            ChannelPool channelPool,
                            RoomBroadcaster roomBroadcaster) {
        this.connectionManager = connectionManager;
        this.messagePublisher  = messagePublisher;
        this.circuitBreaker    = circuitBreaker;
        this.channelPool       = channelPool;
        this.roomBroadcaster   = roomBroadcaster;
        this.startTime         = Instant.now();
    }

    /**
     * Returns server health status and runtime metrics.
     * Used by ALB health checks and monitoring scripts.
     *
     * @return map of health and metrics data
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();

        // Server status
        response.put("status", "UP");
        response.put("service", "ChatFlow WebSocket Server v2");
        response.put("timestamp", Instant.now().toString());
        response.put("startTime", startTime.toString());
        response.put("uptime", calculateUptime());

        // Connection statistics
        response.put("activeConnections", connectionManager.getTotalConnections());
        response.put("activeRooms", connectionManager.getRoomCount());
        response.put("activeUsers", connectionManager.getActiveUserCount());
        response.put("messagesProcessed", connectionManager.getTotalMessagesProcessed());

        // RabbitMQ publish metrics
        response.put("publishSuccess", messagePublisher.getPublishSuccess());
        response.put("publishFailures", messagePublisher.getPublishFailures());

        // Circuit breaker state
        response.put("circuitBreakerState", circuitBreaker.getState().name());
        response.put("circuitBreakerFailureCount", circuitBreaker.getFailureCount());

        // Channel pool availability
        response.put("channelPoolAvailable", channelPool.getAvailableChannels());
        response.put("channelPoolSize", channelPool.getActualPoolSize());

        // Broadcast metrics
        response.put("totalDelivered", roomBroadcaster.getTotalDelivered());
        response.put("totalBroadcastFailed", roomBroadcaster.getTotalFailed());

        return response;
    }

    /**
     * Calculates the server uptime as a human-readable string.
     *
     * @return uptime string in format "Xh Ym Zs"
     */
    private String calculateUptime() {
        long uptimeSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        long hours   = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}