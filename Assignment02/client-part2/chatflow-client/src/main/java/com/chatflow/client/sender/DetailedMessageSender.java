// package com.chatflow.client.sender;

// import com.chatflow.client.config.ClientConfig;
// import com.chatflow.client.model.ChatMessage;
// import com.chatflow.client.queue.MessageQueue;
// import com.chatflow.client.metrics.DetailedMetricsCollector;
// import com.fasterxml.jackson.databind.ObjectMapper;

// import java.util.concurrent.TimeUnit;

// /**
//  * Detailed Message Sender with sampling strategy
//  * Measures latency for 10% of messages, fast-sends the rest
//  */
// public class DetailedMessageSender implements Runnable {
    
//     private final MessageQueue messageQueue;
//     private final DetailedMetricsCollector metricsCollector;
//     private final ConnectionPool connectionPool;
//     private final int messagesToSend;
//     private final ObjectMapper objectMapper;
    
//     // Sampling: measure latency for every Nth message
//     private static final int LATENCY_SAMPLE_RATE = 20; // 5% sampling
    
//     public DetailedMessageSender(MessageQueue messageQueue, 
//                                 DetailedMetricsCollector metricsCollector,
//                                 ConnectionPool connectionPool,
//                                 int messagesToSend) {
//         this.messageQueue = messageQueue;
//         this.metricsCollector = metricsCollector;
//         this.connectionPool = connectionPool;
//         this.messagesToSend = messagesToSend;
//         this.objectMapper = new ObjectMapper();
//     }
    
//     @Override
//     public void run() {
//         int sent = 0;
//         int failed = 0;
        
//         try {
//             for (int i = 0; i < messagesToSend; i++) {
//                 ChatMessage message = messageQueue.poll(30, TimeUnit.SECONDS);
                
//                 if (message == null) {
//                     System.err.println("  ⚠️  Thread timeout waiting for message");
//                     break;
//                 }
                
//                 // Sampling: measure latency for every 10th message
//                 boolean measureLatency = (i % LATENCY_SAMPLE_RATE == 0);
                
//                 boolean success = sendMessage(message, measureLatency);
                
//                 if (success) {
//                     sent++;
//                 } else {
//                     failed++;
//                 }
//             }
            
//         } catch (Exception e) {
//             System.err.println("  ❌ Thread error: " + e.getMessage());
//         }
        
//         System.out.println("  ✅ Thread " + Thread.currentThread().getName() + 
//                          " completed: sent=" + sent + ", failed=" + failed);
//     }
    
//     /**
//      * Send message with optional latency measurement
//      */
//     private boolean sendMessage(ChatMessage message, boolean measureLatency) {
//         long sendTime = System.currentTimeMillis();
        
//         try {
//             ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId());
//             String jsonMessage = objectMapper.writeValueAsString(message);
            
//             if (measureLatency) {
//                 // Wait for response and measure latency
//                 boolean success = client.sendAndMeasureLatency(jsonMessage, 3000);
//                 long latency = client.getLastLatency();
                
//                 metricsCollector.recordMessage(
//                     sendTime,
//                     message.getMessageType(),
//                     latency,
//                     success ? 200 : 400,
//                     message.getRoomId()
//                 );
                
//                 return success;
                
//             } else {
//                 // Fast send without waiting for response
//                 boolean sent = client.sendMessageFast(jsonMessage);
                
//                 // Record to CSV with latency = 0 (not measured)
//                 metricsCollector.recordMessage(
//                     sendTime,
//                     message.getMessageType(),
//                     0,  // latency not measured for fast-send messages
//                     sent ? 200 : 400,
//                     message.getRoomId()
//                 );
                
//                 return sent;
//             }
            
//         } catch (Exception e) {
//             metricsCollector.recordMessage(
//                 sendTime,
//                 message.getMessageType(),
//                 0,
//                 500,
//                 message.getRoomId()
//             );
//             metricsCollector.incrementReconnections();
//             return false;
//         }
//     }
// }


package com.chatflow.client.sender;

import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.metrics.DetailedMetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detailed Message Sender - measures RTT latency for 100% messages.
 * This satisfies Assignment 1 Part 2 requirement and TA feedback.
 */
public class DetailedMessageSender implements Runnable {

    private final MessageQueue messageQueue;
    private final DetailedMetricsCollector metricsCollector;
    private final ConnectionPool connectionPool;
    private final int messagesToSend;
    private final ObjectMapper objectMapper;

    // RTT timeout per message (ms) - you can tune
    private static final long ACK_TIMEOUT_MS = 5000;
    private final ConcurrentHashMap<String, AtomicInteger> roomCounters = new ConcurrentHashMap<>();

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

                boolean success = sendMessageWithRtt(message);
                if (success) sent++;
                else failed++;
            }

        } catch (Exception e) {
            System.err.println("  ❌ Thread error: " + e.getMessage());
        }

    }

    // private boolean sendMessageWithRtt(ChatMessage message) {
    //     long sendTimestamp = System.currentTimeMillis();

    //     try {
    //         // Ensure messageId exists
    //         if (message.getMessageId() == null || message.getMessageId().isEmpty()) {
    //             message.setMessageId(ChatWebSocketClient.newMessageId());
    //         }

    //         ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId());
    //         String jsonMessage = objectMapper.writeValueAsString(message);
    //         // System.out.println("CLIENT SEND JSON: " + jsonMessage);
    //         ChatWebSocketClient.AckResult ack = client.sendAndAwaitAck(
    //                 jsonMessage,
    //                 message.getMessageId(),
    //                 ACK_TIMEOUT_MS
    //         );

    //         metricsCollector.recordMessage(
    //                 sendTimestamp,
    //                 message.getMessageType(),
    //                 ack.latencyMs,
    //                 ack.success ? 200 : 400,
    //                 message.getRoomId()
    //         );

    //         return ack.success;

    //     } catch (Exception e) {
    //         // RTT not received -> failure (correct for load test)
    //         metricsCollector.recordMessage(
    //                 sendTimestamp,
    //                 message.getMessageType(),
    //                 ACK_TIMEOUT_MS,    // latency unknown due to timeout/error
    //                 500,
    //                 message.getRoomId()
    //         );
    //         metricsCollector.incrementReconnections();
    //         return false;
    //     }
    // }
    // private boolean sendMessageWithRtt(ChatMessage message) {
    //     long sendTimestamp = System.currentTimeMillis();

    //     int maxRetries = 3;
        
    //     for (int attempt = 0; attempt < maxRetries; attempt++) {
    //         try {
    //             if (message.getMessageId() == null || message.getMessageId().isEmpty()) {
    //                 message.setMessageId(ChatWebSocketClient.newMessageId());
    //             }

    //             ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId());
                
    //             if (!client.isConnected()) {
    //                 connectionPool.removeConnection(message.getRoomId());
    //                 continue; 
    //             }

    //             String jsonMessage = objectMapper.writeValueAsString(message);
    //             ChatWebSocketClient.AckResult ack = client.sendAndAwaitAck(
    //                     jsonMessage,
    //                     message.getMessageId(),
    //                     ACK_TIMEOUT_MS
    //             );

    //             metricsCollector.recordMessage(
    //                     sendTimestamp,
    //                     message.getMessageType(),
    //                     ack.latencyMs,
    //                     ack.success ? 200 : 400,
    //                     message.getRoomId()
    //             );

    //             return ack.success;

    //         } catch (Exception e) {
    //             // 
    //             connectionPool.removeConnection(message.getRoomId());
    //             if (attempt == maxRetries - 1) {
    //                 metricsCollector.recordMessage(
    //                         sendTimestamp,
    //                         message.getMessageType(),
    //                         ACK_TIMEOUT_MS,
    //                         500,
    //                         message.getRoomId()
    //                 );
    //                 metricsCollector.incrementReconnections();
    //                 return false;
    //             }
    //         }
    //     }
    //     return false;
    // }

    private boolean sendMessageWithRtt(ChatMessage message) {
        long sendTimestamp = System.currentTimeMillis();
        int maxRetries = 3;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            int slot = Math.abs(
                    roomCounters
                        .computeIfAbsent(message.getRoomId(), r -> new AtomicInteger(0))
                        .getAndIncrement()
            ) % ClientConfig.CONNECTIONS_PER_ROOM;

            try {
                if (message.getMessageId() == null || message.getMessageId().isEmpty()) {
                    message.setMessageId(ChatWebSocketClient.newMessageId());
                }

                ChatWebSocketClient client = connectionPool.getConnection(message.getRoomId(), slot);

                if (!client.isConnected()) {
                    connectionPool.removeConnection(message.getRoomId(), slot);
                    continue;
                }

                String jsonMessage = objectMapper.writeValueAsString(message);
                ChatWebSocketClient.AckResult ack = client.sendAndAwaitAck(
                        jsonMessage,
                        message.getMessageId(),
                        ACK_TIMEOUT_MS
                );

                metricsCollector.recordMessage(
                        sendTimestamp,
                        message.getMessageType(),
                        ack.latencyMs,
                        ack.success ? 200 : 400,
                        message.getRoomId()
                );

                return ack.success;

            } catch (Exception e) {
                connectionPool.removeConnection(message.getRoomId(), slot);

                if (attempt == maxRetries - 1) {
                    metricsCollector.recordMessage(
                            sendTimestamp,
                            message.getMessageType(),
                            ACK_TIMEOUT_MS,
                            500,
                            message.getRoomId()
                    );
                    metricsCollector.incrementReconnections();
                    return false;
                }
            }
        }
        return false;
    }
}