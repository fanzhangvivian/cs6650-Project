package com.chatflow.client.sender;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket client with improved latency tracking
 */
public class ChatWebSocketClient extends WebSocketClient {
    
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private volatile boolean isConnected = false;
    private final AtomicLong lastSendTime = new AtomicLong(0);
    private final AtomicLong lastLatency = new AtomicLong(0);
    private final AtomicBoolean lastMessageSuccess = new AtomicBoolean(false);
    private volatile CountDownLatch responseLatch = null;
    
    public ChatWebSocketClient(URI serverUri) {
        super(serverUri);
        setConnectionLostTimeout(10);
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        isConnected = true;
        connectLatch.countDown();
    }
    
    @Override
    public void onMessage(String message) {
        // Calculate latency
        long receiveTime = System.currentTimeMillis();
        long sendTime = lastSendTime.get();
        
        if (sendTime > 0) {
            long latency = receiveTime - sendTime;
            lastLatency.set(latency);
        }
        
        // Check success
        lastMessageSuccess.set(message.contains("SUCCESS"));
        
        // Signal response received
        if (responseLatch != null) {
            responseLatch.countDown();
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
    }
    
    @Override
    public void onError(Exception ex) {
        isConnected = false;
        connectLatch.countDown();
        if (responseLatch != null) {
            responseLatch.countDown();
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
    
    /**
     * Send message and wait for response to measure latency
     */
    public boolean sendAndMeasureLatency(String message, long timeoutMs) {
        if (!isOpen()) {
            return false;
        }
        
        // Reset response latch
        responseLatch = new CountDownLatch(1);
        lastMessageSuccess.set(false);
        lastLatency.set(0);
        
        // Record send time and send
        lastSendTime.set(System.currentTimeMillis());
        send(message);
        
        // Wait for response
        try {
            boolean received = responseLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return received && lastMessageSuccess.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Get latency of last message
     */
    public long getLastLatency() {
        return lastLatency.get();
    }
    
    /**
     * Check if last message was successful
     */
    public boolean wasLastMessageSuccessful() {
        return lastMessageSuccess.get();
    }
    
    public boolean isConnected() {
        return isConnected && isOpen();
    }

    /**
     * Fast send without waiting for response (for non-sampled messages)
     */
    public boolean sendMessageFast(String message) {
        if (isOpen()) {
            send(message);
            return true;
        }
        return false;
    }
}