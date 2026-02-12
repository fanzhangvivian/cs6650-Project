package com.chatflow.client.generator;

import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.model.ChatMessage;
import com.chatflow.client.model.MessageType;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.util.MessageLoader;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Message Generator (Producer)
 * Single thread that generates all 500,000 messages and puts them in the queue
 */
public class MessageGenerator implements Runnable {
    
    private final MessageQueue messageQueue;
    private final int totalMessages;
    private final List<String> predefinedMessages;
    
    public MessageGenerator(MessageQueue messageQueue, int totalMessages) {
        this.messageQueue = messageQueue;
        this.totalMessages = totalMessages;
        this.predefinedMessages = MessageLoader.loadMessages();
        
        System.out.println("MessageGenerator initialized with " + 
                         predefinedMessages.size() + " predefined messages");
    }
    
    @Override
    public void run() {
        System.out.println("🔄 MessageGenerator started - generating " + 
                         totalMessages + " messages...");
        
        long startTime = System.currentTimeMillis();
        int generatedCount = 0;
        
        try {
            for (int i = 0; i < totalMessages; i++) {
                ChatMessage message = generateMessage();
                messageQueue.put(message);
                generatedCount++;
                
                // Progress update every 50,000 messages
                if ((i + 1) % 50000 == 0) {
                    System.out.println("  Generated: " + (i + 1) + " messages");
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("MessageGenerator completed: " + generatedCount + 
                             " messages in " + duration + "ms");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("MessageGenerator interrupted after " + 
                             generatedCount + " messages");
        }
    }
    
    /**
     * Generate a single random chat message
     */
    private ChatMessage generateMessage() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Generate random userId (1-100,000)
        int userId = random.nextInt(
            ClientConfig.MIN_USER_ID, 
            ClientConfig.MAX_USER_ID + 1
        );
        
        // Generate username from userId
        String username = "user" + userId;
        
        // Select random message from predefined messages
        String messageText = predefinedMessages.get(
            random.nextInt(predefinedMessages.size())
        );
        
        // Generate random roomId (1-20)
        int roomId = random.nextInt(
            ClientConfig.MIN_ROOM_ID, 
            ClientConfig.MAX_ROOM_ID + 1
        );
        
        // Generate messageType with probability distribution
        // 90% TEXT, 5% JOIN, 5% LEAVE
        MessageType messageType = generateMessageType(random);
        
        // Current timestamp in ISO-8601 format
        String timestamp = Instant.now().toString();
        
        // Create message
        ChatMessage message = new ChatMessage(
            String.valueOf(userId),
            username,
            messageText,
            timestamp,
            messageType
        );
        message.setRoomId(String.valueOf(roomId));
        
        return message;
    }
    
    /**
     * Generate message type based on probability distribution
     * 90% TEXT, 5% JOIN, 5% LEAVE
     */
    private MessageType generateMessageType(ThreadLocalRandom random) {
        double r = random.nextDouble();
        
        if (r < ClientConfig.TEXT_PROBABILITY) {
            return MessageType.TEXT;
        } else if (r < ClientConfig.TEXT_PROBABILITY + ClientConfig.JOIN_PROBABILITY) {
            return MessageType.JOIN;
        } else {
            return MessageType.LEAVE;
        }
    }
}