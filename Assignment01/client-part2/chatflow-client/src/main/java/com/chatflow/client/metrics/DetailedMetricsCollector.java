package com.chatflow.client.metrics;

import com.chatflow.client.model.MessageRecord;
import com.chatflow.client.model.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detailed metrics collector for Client Part 2
 * Records latency for each message
 */
public class DetailedMetricsCollector extends BasicMetricsCollector {
    
    // Thread-safe queue for storing message records
    private final ConcurrentLinkedQueue<MessageRecord> messageRecords;
    
    public DetailedMetricsCollector() {
        super();
        this.messageRecords = new ConcurrentLinkedQueue<>();
    }
    
    /**
     * Record a message with latency
     */
    public void recordMessage(long timestamp, MessageType messageType, 
                             long latencyMs, int statusCode, String roomId) {
        MessageRecord record = new MessageRecord(
            timestamp, messageType, latencyMs, statusCode, roomId
        );
        messageRecords.add(record);
        
        // Also update basic metrics
        if (statusCode == 200) {
            recordSuccess();
        } else {
            recordFailure();
        }
    }
    
    /**
     * Get all message records as a list
     */
    public List<MessageRecord> getMessageRecords() {
        return new ArrayList<>(messageRecords);
    }
    
    /**
     * Get number of records
     */
    public int getRecordCount() {
        return messageRecords.size();
    }
}