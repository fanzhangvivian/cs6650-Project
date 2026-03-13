package com.chatflow.consumer.controller;

import com.chatflow.consumer.service.MessageConsumerService;
import com.chatflow.consumer.service.ServerHttpClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint for the Consumer application.
 * Exposes consumer metrics including messages consumed,
 * broadcast success/failure counts, and active consumer count.
 */
@RestController
public class HealthController {

    private final MessageConsumerService messageConsumerService;
    private final ServerHttpClient serverHttpClient;
    private final Instant startTime;

    public HealthController(MessageConsumerService messageConsumerService,
                            ServerHttpClient serverHttpClient) {
        this.messageConsumerService = messageConsumerService;
        this.serverHttpClient       = serverHttpClient;
        this.startTime              = Instant.now();
    }

    /**
     * Returns consumer health status and runtime metrics.
     *
     * @return map of health and metrics data
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();

        // Service status
        response.put("status", "UP");
        response.put("service", "ChatFlow Consumer");
        response.put("timestamp", Instant.now().toString());
        response.put("startTime", startTime.toString());
        response.put("uptime", calculateUptime());

        // Consumer metrics
        response.put("messagesConsumed", messageConsumerService.getMessagesConsumed());
        response.put("messagesFailed", messageConsumerService.getMessagesFailed());
        response.put("activeConsumers", messageConsumerService.getActiveConsumerCount());

        // Broadcast metrics
        response.put("broadcastSuccess", serverHttpClient.getBroadcastSuccess());
        response.put("broadcastFailed", serverHttpClient.getBroadcastFailed());

        return response;
    }

    /**
     * Calculates the consumer uptime as a human-readable string.
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