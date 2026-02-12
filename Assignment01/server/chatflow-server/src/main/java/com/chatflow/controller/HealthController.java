package com.chatflow.controller;

import com.chatflow.service.ConnectionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint
 */
@RestController
public class HealthController {
    
    private final ConnectionManager connectionManager;
    private final Instant startTime;
    
    public HealthController(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.startTime = Instant.now();
    }
    
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        
        response.put("status", "UP");
        response.put("service", "ChatFlow WebSocket Server");
        response.put("timestamp", Instant.now().toString());
        response.put("startTime", startTime.toString());
        response.put("uptime", calculateUptime());
        
        // Connection statistics
        response.put("activeConnections", connectionManager.getTotalConnections());
        response.put("activeRooms", connectionManager.getRoomCount());
        response.put("messagesProcessed", connectionManager.getTotalMessagesProcessed());
        
        return response;
    }
    
    private String calculateUptime() {
        long uptimeSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        long hours = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}