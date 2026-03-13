package com.chatflow.client.sender;

import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.metrics.BasicMetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

/**
 * Optimized Message Sender - uses connection pool
 */
public class MessageSender implements Runnable {
    
    private final MessageQueue messageQueue;
    private final BasicMetricsCollector metricsCollector;
    private final ConnectionPool connectionPool;
    private final int messagesToSend;
    private final ObjectMapper objectMapper;
    
    public MessageSender(MessageQueue messageQueue, 
                        BasicMetricsCollector metricsCollector,
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
                // Get message from queue with timeout
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                
                if (message == null) {
                    System.err.println("  ⚠️  Thread timeout waiting for message");
                    break;
                }
                
                // Send message
                boolean success = sendMessage(message);
                
                if (success) {
                    sent++;
                    metricsCollector.recordSuccess();
                } else {
                    failed++;
                    metricsCollector.recordFailure();
                }
            }
            
        } catch (Exception e) {
            System.err.println("  ❌ Thread error: " + e.getMessage());
        }
        
        System.out.println("  ✅ Thread " + Thread.currentThread().getName() + 
                         " completed: sent=" + sent + ", failed=" + failed);
    }
    
    /**
     * Send single message with simple retry
     */
    private boolean sendMessage(ChatMessage message) {
        int retries = 0;
        
        while (retries < ClientConfig.MAX_RETRIES) {
            try {
                // Get connection from pool (reuses existing connections)
                ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId());
                
                if (client == null) {
                    metricsCollector.incrementTotalConnections();
                }
                
                // Convert to JSON and send
                String jsonMessage = objectMapper.writeValueAsString(message);
                
                if (client.sendMessageFast(jsonMessage)) {
                    return true;
                }
                
                retries++;
                if (retries < ClientConfig.MAX_RETRIES) {
                    Thread.sleep(ClientConfig.INITIAL_BACKOFF_MS * retries);
                }
                
            } catch (Exception e) {
                retries++;
                metricsCollector.incrementReconnections();
                
                if (retries < ClientConfig.MAX_RETRIES) {
                    try {
                        Thread.sleep(ClientConfig.INITIAL_BACKOFF_MS * retries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        return false;
    }
}