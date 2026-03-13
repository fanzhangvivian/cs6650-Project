// package com.chatflow.client.sender;

// import java.util.concurrent.atomic.AtomicInteger;
// import com.chatflow.client.config.ClientConfig;

// import java.net.URI;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.atomic.AtomicInteger;

// /**
//  * Connection pool - maintains one connection per room
//  * Reuses connections to avoid connection overhead
//  */
// public class ConnectionPool {
    
//     private final ConcurrentHashMap<String, ChatWebSocketClient> connections;
    
//     private final AtomicInteger totalConnectionsCreated;

//     public ConnectionPool() {
//         this.connections = new ConcurrentHashMap<>();
//         this.totalConnectionsCreated = new AtomicInteger(0);
//     }
    
//     /**
//      * Get or create connection for a room
//      */
//     public ChatWebSocketClient getConnection(String roomId) throws Exception {
//         // Check if we already have a connection for this room
//         ChatWebSocketClient client = connections.get(roomId);
        
//         if (client != null && client.isConnected()) {
//             return client; // Reuse existing connection
//         }
        
//         // Create new connection
//         URI serverUri = URI.create(ClientConfig.SERVER_URL + roomId);
//         client = new ChatWebSocketClient(serverUri);
        
//         client.connect();
        
//         // Wait for connection to establish
//         if (!client.awaitConnection(ClientConfig.CONNECTION_TIMEOUT_MS)) {
//             throw new Exception("Connection timeout for room " + roomId);
//         }
        
//         // Store in pool
//         connections.put(roomId, client);

//         connections.put(roomId, client);
//         totalConnectionsCreated.incrementAndGet(); // NEW: Count new connection
        
//         return client;
//     }
    
//     /**
//      * Close all connections
//      */
//     public void closeAll() {
//         for (ChatWebSocketClient client : connections.values()) {
//             if (client != null && client.isOpen()) {
//                 client.close();
//             }
//         }
//         connections.clear();
//     }
    
//     /**
//      * Get total number of active connections
//      */
//     public int getActiveConnectionCount() {
//         int count = 0;
//         for (ChatWebSocketClient client : connections.values()) {
//             if (client != null && client.isConnected()) {
//                 count++;
//             }
//         }
//         return count;
//     }

//     /**
//      * Get total number of connections created
//      */
//     public int getTotalConnectionsCreated() {
//         return totalConnectionsCreated.get();
//     }
// }

// package com.chatflow.client.sender;

// import com.chatflow.client.config.ClientConfig;

// import java.net.URI;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.atomic.AtomicInteger;

// /**
//  * Connection pool - maintains one connection per room
//  * Reuses connections to avoid connection overhead
//  */
// public class ConnectionPool {

//     private final ConcurrentHashMap<String, ChatWebSocketClient> connections;
//     private final AtomicInteger totalConnectionsCreated;

//     public ConnectionPool() {
//         this.connections = new ConcurrentHashMap<>();
//         this.totalConnectionsCreated = new AtomicInteger(0);
//     }

//     /**
//      * Get or create connection for a room
//      */
//     public ChatWebSocketClient getConnection(String roomId) throws Exception {
        
        
//         ChatWebSocketClient existing = connections.get(roomId);

//         // Reuse if valid
//         if (existing != null && existing.isConnected()) {
//             return existing;
//         }

//         // Build correct WebSocket URI
//         String url = ClientConfig.SERVER_URL + "/" + roomId;
//         URI serverUri = new URI(url);
//         System.out.println("Creating connection to: " + serverUri);

//         ChatWebSocketClient client = new ChatWebSocketClient(serverUri);

//         client.connect();

//         // Wait for connection
//         boolean connected = client.awaitConnection(ClientConfig.CONNECTION_TIMEOUT_MS);

//         if (!connected || !client.isConnected()) {
//             throw new Exception("Failed to connect to " + url);
//         }

//         connections.put(roomId, client);
//         totalConnectionsCreated.incrementAndGet();
        

//         return client;
//     }

//     /**
//      * Close all connections
//      */
//     public void closeAll() {
//         for (ChatWebSocketClient client : connections.values()) {
//             if (client != null && client.isOpen()) {
//                 client.close();
//             }
//         }
//         connections.clear();
//     }

//     /**
//      * Get total number of active connections
//      */
//     public int getActiveConnectionCount() {
//         int count = 0;
//         for (ChatWebSocketClient client : connections.values()) {
//             if (client != null && client.isConnected()) {
//                 count++;
//             }
//         }
//         return count;
//     }

//     /**
//      * Get total number of connections created
//      */
//     public int getTotalConnectionsCreated() {
//         return totalConnectionsCreated.get();
//     }
// }


// package com.chatflow.client.sender;

// import com.chatflow.client.config.ClientConfig;

// import java.net.URI;
// import java.util.List;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.CopyOnWriteArrayList;
// import java.util.concurrent.atomic.AtomicInteger;

// /**
//  * Enhanced ConnectionPool
//  *
//  * Supports multiple connections per room.
//  * Each room can maintain up to MAX_CONNECTIONS_PER_ROOM connections.
//  * Connections are assigned using round-robin.
//  *
//  * This enables high concurrency while still grouping by room.
//  */
// public class ConnectionPool {

//     // roomId -> list of connections
//     private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatWebSocketClient>> connections;

//     // roomId -> round robin index
//     private final ConcurrentHashMap<String, AtomicInteger> roundRobinIndex;

//     private final AtomicInteger totalConnectionsCreated;

//     // 👇 可调参数（关键）
//     private static final int MAX_CONNECTIONS_PER_ROOM = 16;

//     public ConnectionPool() {
//         this.connections = new ConcurrentHashMap<>();
//         this.roundRobinIndex = new ConcurrentHashMap<>();
//         this.totalConnectionsCreated = new AtomicInteger(0);
//     }

//     /**
//      * Get a connection for a room using round-robin.
//      * Creates new connection if under limit.
//      */
//     public ChatWebSocketClient getConnection(String roomId) throws Exception {

//         connections.putIfAbsent(roomId, new CopyOnWriteArrayList<>());
//         roundRobinIndex.putIfAbsent(roomId, new AtomicInteger(0));

//         List<ChatWebSocketClient> roomConnections = connections.get(roomId);

//         // 1️⃣ If under limit, create new connection
//         if (roomConnections.size() < MAX_CONNECTIONS_PER_ROOM) {
//             ChatWebSocketClient client = createConnection(roomId);
//             roomConnections.add(client);
//             return client;
//         }

//         // 2️⃣ Otherwise use round-robin
//         int index = Math.abs(roundRobinIndex.get(roomId).getAndIncrement());
//         return roomConnections.get(index % roomConnections.size());
//     }

//     /**
//      * Create new WebSocket connection
//      */
//     private ChatWebSocketClient createConnection(String roomId) throws Exception {

//         String url = ClientConfig.SERVER_URL + "/" + roomId;
//         URI serverUri = new URI(url);

//         ChatWebSocketClient client = new ChatWebSocketClient(serverUri);

//         client.connect();

//         boolean connected = client.awaitConnection(ClientConfig.CONNECTION_TIMEOUT_MS);

//         if (!connected || !client.isConnected()) {
//             throw new Exception("Failed to connect to " + url);
//         }

//         totalConnectionsCreated.incrementAndGet();

//         return client;
//     }

//     /**
//      * Close all connections
//      */
//     public void closeAll() {
//         for (List<ChatWebSocketClient> roomList : connections.values()) {
//             for (ChatWebSocketClient client : roomList) {
//                 if (client != null && client.isOpen()) {
//                     client.close();
//                 }
//             }
//         }
//         connections.clear();
//     }

//     /**
//      * Get total active connections
//      */
//     public int getActiveConnectionCount() {
//         int count = 0;
//         for (List<ChatWebSocketClient> roomList : connections.values()) {
//             for (ChatWebSocketClient client : roomList) {
//                 if (client != null && client.isConnected()) {
//                     count++;
//                 }
//             }
//         }
//         return count;
//     }

//     /**
//      * Total connections ever created
//      */
//     public int getTotalConnectionsCreated() {
//         return totalConnectionsCreated.get();
//     }
// }


package com.chatflow.client.sender;

import com.chatflow.client.config.ClientConfig;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe Connection Pool
 * Maintains ONE connection per room using computeIfAbsent
 */
public class ConnectionPool {

    private final ConcurrentHashMap<String, ChatWebSocketClient> connections;
    private final AtomicInteger totalConnectionsCreated;

    public ConnectionPool() {
        this.connections = new ConcurrentHashMap<>();
        this.totalConnectionsCreated = new AtomicInteger(0);
    }

    /**
     * Thread-safe get or create connection
     */
    public ChatWebSocketClient getConnection(String roomId, int slot) throws Exception {
        String connectionKey = roomId + "-" + slot;

        ChatWebSocketClient existing = connections.get(connectionKey);
        if (existing != null) {
            if (existing.isConnected()) {
                return existing;
            } else {
                connections.remove(connectionKey, existing);
            }
        }

        return connections.computeIfAbsent(connectionKey, key -> {
            try {
                String url = ClientConfig.SERVER_URL + "/" + roomId;
                URI serverUri = new URI(url);
                ChatWebSocketClient client = new ChatWebSocketClient(serverUri);
                client.connect();
                boolean connected = client.awaitConnection(ClientConfig.CONNECTION_TIMEOUT_MS);
                if (!connected || !client.isConnected()) {
                    throw new RuntimeException("Failed to connect to " + url);
                }
                totalConnectionsCreated.incrementAndGet();
                return client;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void removeConnection(String roomId, int slot) {
        connections.remove(roomId + "-" + slot);
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
     * Active connections (currently connected)
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
     * Total connections ever created
     */
    public int getTotalConnectionsCreated() {
        return totalConnectionsCreated.get();
    }
} 