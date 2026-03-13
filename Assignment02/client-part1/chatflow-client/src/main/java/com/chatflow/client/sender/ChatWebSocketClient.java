package com.chatflow.client.sender;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Optimized WebSocket client - no artificial delays
 */
public class ChatWebSocketClient extends WebSocketClient {
    
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private volatile boolean isConnected = false;
    
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
        // Receive response but don't block
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
    }
    
    @Override
    public void onError(Exception ex) {
        isConnected = false;
        connectLatch.countDown();
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
     * Send message - fire and forget (optimized for throughput)
     */
    public boolean sendMessageFast(String message) {
        if (isOpen()) {
            send(message);
            return true;
        }
        return false;
    }
    
    public boolean isConnected() {
        return isConnected && isOpen();
    }
}