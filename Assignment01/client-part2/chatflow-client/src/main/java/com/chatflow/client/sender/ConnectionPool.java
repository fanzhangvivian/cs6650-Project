package com.chatflow.client.sender;

import java.util.concurrent.atomic.AtomicInteger;
import com.chatflow.client.config.ClientConfig;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection pool - maintains one connection per room
 * Reuses connections to avoid connection overhead
 */
public class ConnectionPool {
    
    private final ConcurrentHashMap<String, ChatWebSocketClient> connections;
    
    private final AtomicInteger totalConnectionsCreated;

    public ConnectionPool() {
        this.connections = new ConcurrentHashMap<>();
        this.totalConnectionsCreated = new AtomicInteger(0);
    }
    
    /**
     * Get or create connection for a room
     */
    public ChatWebSocketClient getConnection(String roomId) throws Exception {
        // Check if we already have a connection for this room
        ChatWebSocketClient client = connections.get(roomId);
        
        if (client != null && client.isConnected()) {
            return client; // Reuse existing connection
        }
        
        // Create new connection
        URI serverUri = URI.create(ClientConfig.SERVER_URL + roomId);
        client = new ChatWebSocketClient(serverUri);
        
        client.connect();
        
        // Wait for connection to establish
        if (!client.awaitConnection(ClientConfig.CONNECTION_TIMEOUT_MS)) {
            throw new Exception("Connection timeout for room " + roomId);
        }
        
        // Store in pool
        connections.put(roomId, client);

        connections.put(roomId, client);
        totalConnectionsCreated.incrementAndGet(); // NEW: Count new connection
        
        return client;
    }
    
    /**
     * Close all connections
     */
    public void closeAll() {
        for (ChatWebSocketClient client : connections.values()) {
            if (client != null && client.isOpen()) {
                client.close();
            }
        }
        connections.clear();
    }
    
    /**
     * Get total number of active connections
     */
    public int getActiveConnectionCount() {
        int count = 0;
        for (ChatWebSocketClient client : connections.values()) {
            if (client != null && client.isConnected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get total number of connections created
     */
    public int getTotalConnectionsCreated() {
        return totalConnectionsCreated.get();
    }
}