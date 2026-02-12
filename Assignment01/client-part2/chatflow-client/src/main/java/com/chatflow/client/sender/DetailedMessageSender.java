package com.chatflow.client.sender;

import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.metrics.DetailedMetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

/**
 * Detailed Message Sender with sampling strategy
 * Measures latency for 10% of messages, fast-sends the rest
 */
public class DetailedMessageSender implements Runnable {
    
    private final MessageQueue messageQueue;
    private final DetailedMetricsCollector metricsCollector;
    private final ConnectionPool connectionPool;
    private final int messagesToSend;
    private final ObjectMapper objectMapper;
    
    // Sampling: measure latency for every Nth message
    private static final int LATENCY_SAMPLE_RATE = 20; // 5% sampling
    
    public DetailedMessageSender(MessageQueue messageQueue, 
                                DetailedMetricsCollector metricsCollector,
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
                ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                
                if (message == null) {
                    System.err.println("  ⚠️  Thread timeout waiting for message");
                    break;
                }
                
                // Sampling: measure latency for every 10th message
                boolean measureLatency = (i % LATENCY_SAMPLE_RATE == 0);
                
                boolean success = sendMessage(message, measureLatency);
                
                if (success) {
                    sent++;
                } else {
                    failed++;
                }
            }
            
        } catch (Exception e) {
            System.err.println("  ❌ Thread error: " + e.getMessage());
        }
        
        System.out.println("  ✅ Thread " + Thread.currentThread().getName() + 
                         " completed: sent=" + sent + ", failed=" + failed);
    }
    
    /**
     * Send message with optional latency measurement
     */
    private boolean sendMessage(ChatMessage message, boolean measureLatency) {
        long sendTime = System.currentTimeMillis();
        
        try {
            ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId());
            String jsonMessage = objectMapper.writeValueAsString(message);
            
            if (measureLatency) {
                // Wait for response and measure latency
                boolean success = client.sendAndMeasureLatency(jsonMessage, 3000);
                long latency = client.getLastLatency();
                
                metricsCollector.recordMessage(
                    sendTime,
                    message.getMessageType(),
                    latency,
                    success ? 200 : 400,
                    message.getRoomId()
                );
                
                return success;
                
            } else {
                // Fast send without waiting for response
                boolean sent = client.sendMessageFast(jsonMessage);
                
                // Record to CSV with latency = 0 (not measured)
                metricsCollector.recordMessage(
                    sendTime,
                    message.getMessageType(),
                    0,  // latency not measured for fast-send messages
                    sent ? 200 : 400,
                    message.getRoomId()
                );
                
                return sent;
            }
            
        } catch (Exception e) {
            metricsCollector.recordMessage(
                sendTime,
                message.getMessageType(),
                0,
                500,
                message.getRoomId()
            );
            metricsCollector.incrementReconnections();
            return false;
        }
    }
}