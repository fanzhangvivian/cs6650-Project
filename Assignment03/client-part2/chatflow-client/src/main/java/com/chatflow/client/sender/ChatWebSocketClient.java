// package com.chatflow.client.sender;

// import org.java_websocket.client.WebSocketClient;
// import org.java_websocket.handshake.ServerHandshake;

// import java.net.URI;
// import java.util.concurrent.CountDownLatch;
// import java.util.concurrent.TimeUnit;
// import java.util.concurrent.atomic.AtomicLong;
// import java.util.concurrent.atomic.AtomicBoolean;

// /**
//  * WebSocket client with improved latency tracking
//  */
// public class ChatWebSocketClient extends WebSocketClient {
    
//     private final CountDownLatch connectLatch = new CountDownLatch(1);
//     private volatile boolean isConnected = false;
//     private final AtomicLong lastSendTime = new AtomicLong(0);
//     private final AtomicLong lastLatency = new AtomicLong(0);
//     private final AtomicBoolean lastMessageSuccess = new AtomicBoolean(false);
//     private volatile CountDownLatch responseLatch = null;
    
//     public ChatWebSocketClient(URI serverUri) {
//         super(serverUri);
//         setConnectionLostTimeout(10);
//     }
    
//     @Override
//     public void onOpen(ServerHandshake handshake) {
//         isConnected = true;
//         connectLatch.countDown();
//     }
    
//     @Override
//     public void onMessage(String message) {
//         // Calculate latency
//         long receiveTime = System.currentTimeMillis();
//         long sendTime = lastSendTime.get();
        
//         if (sendTime > 0) {
//             long latency = receiveTime - sendTime;
//             lastLatency.set(latency);
//         }
        
//         // Check success
//         lastMessageSuccess.set(message.contains("SUCCESS"));
        
//         // Signal response received
//         if (responseLatch != null) {
//             responseLatch.countDown();
//         }
//     }
    
//     @Override
//     public void onClose(int code, String reason, boolean remote) {
//         isConnected = false;
//     }
    
//     @Override
//     public void onError(Exception ex) {
//         isConnected = false;
//         connectLatch.countDown();
//         if (responseLatch != null) {
//             responseLatch.countDown();
//         }
//     }
    
//     public boolean awaitConnection(long timeoutMs) {
//         try {
//             return connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
//         } catch (InterruptedException e) {
//             Thread.currentThread().interrupt();
//             return false;
//         }
//     }
    
//     /**
//      * Send message and wait for response to measure latency
//      */
//     public boolean sendAndMeasureLatency(String message, long timeoutMs) {
//         if (!isOpen()) {
//             return false;
//         }
        
//         // Reset response latch
//         responseLatch = new CountDownLatch(1);
//         lastMessageSuccess.set(false);
//         lastLatency.set(0);
        
//         // Record send time and send
//         lastSendTime.set(System.currentTimeMillis());
//         send(message);
        
//         // Wait for response
//         try {
//             boolean received = responseLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
//             return received && lastMessageSuccess.get();
//         } catch (InterruptedException e) {
//             Thread.currentThread().interrupt();
//             return false;
//         }
//     }
    
//     /**
//      * Get latency of last message
//      */
//     public long getLastLatency() {
//         return lastLatency.get();
//     }
    
//     /**
//      * Check if last message was successful
//      */
//     public boolean wasLastMessageSuccessful() {
//         return lastMessageSuccess.get();
//     }
    
//     public boolean isConnected() {
//         return isConnected && isOpen();
//     }

//     /**
//      * Fast send without waiting for response (for non-sampled messages)
//      */
//     public boolean sendMessageFast(String message) {
//         if (isOpen()) {
//             send(message);
//             return true;
//         }
//         return false;
//     }
// }



package com.chatflow.client.sender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatWebSocketClient extends WebSocketClient {

    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final ObjectMapper mapper = new ObjectMapper();

    // messageId -> pending future
    private final ConcurrentHashMap<String, CompletableFuture<AckResult>> pending = new ConcurrentHashMap<>();

    // hard cap to avoid memory leak if server stops responding
    private static final int MAX_PENDING = 5000;

    public ChatWebSocketClient(URI serverUri) {
        super(serverUri);
        setConnectionLostTimeout(60);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        connected.set(true);
        connectLatch.countDown();
        // System.out.println("WEBSOCKET OPENED");
    }

    @Override
    public void onMessage(String message) {
        // System.out.println("SERVER RESPONSE: " + message);
        String messageId = null;
        boolean success = false;

        try {
            JsonNode root = mapper.readTree(message);

            // messageId nested under originalMessage
            if (root.has("originalMessage") &&
                root.get("originalMessage").hasNonNull("messageId")) {
                messageId = root.get("originalMessage")
                                .get("messageId")
                                .asText();
            }

            // success field
            if (root.hasNonNull("status")) {
                String st = root.get("status").asText("");
                success = "SUCCESS".equalsIgnoreCase(st);
            } else {
                success = message.contains("SUCCESS");
            }

        } catch (Exception e) {
            success = message.contains("SUCCESS");
        }

        if (messageId == null) {
            return; // cannot correlate
        }

        CompletableFuture<AckResult> fut = pending.remove(messageId);
        if (fut != null) {
            fut.complete(new AckResult(success));
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        connected.set(false);
        failAllPending(new RuntimeException("WebSocket closed: " + reason));
    }

    @Override
    public void onError(Exception ex) {
        System.out.println("WEBSOCKET ERROR: " + ex.getMessage());
        connected.set(false);
        connectLatch.countDown();
        failAllPending(ex);
    }

    private void failAllPending(Exception ex) {
        for (String key : pending.keySet()) {
            CompletableFuture<AckResult> fut = pending.remove(key);
            if (fut != null) {
                fut.completeExceptionally(ex);
            }
        }
    }

    public boolean awaitConnection(long timeoutMs) {
        try {
            return connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isConnected() {
        return connected.get() && isOpen();
    }

    /**
     * Send a JSON message (already includes messageId) and wait for echo/ack.
     * Returns AckResult including success + latency.
     */
    public AckResult sendAndAwaitAck(String json, String messageId, long timeoutMs) throws Exception {
        if (!isOpen()) {
            throw new IllegalStateException("WebSocket not open");
        }

        if (pending.size() > MAX_PENDING) {
            throw new RejectedExecutionException("Too many pending messages on this connection");
        }

        long sendTime = System.currentTimeMillis();
        CompletableFuture<AckResult> fut = new CompletableFuture<>();
        pending.put(messageId, fut);

        // send after put to avoid race where response arrives immediately
        send(json);

        AckResult ack = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        long latency = System.currentTimeMillis() - sendTime;
        return new AckResult(ack.success, latency);
    }

    // Helper: generate messageId if caller wants
    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    public static class AckResult {
        public final boolean success;
        public final long latencyMs;

        public AckResult(boolean success) {
            this(success, 0);
        }

        public AckResult(boolean success, long latencyMs) {
            this.success = success;
            this.latencyMs = latencyMs;
        }
    }
}