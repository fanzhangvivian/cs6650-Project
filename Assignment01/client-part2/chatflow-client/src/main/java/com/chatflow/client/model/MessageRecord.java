package com.chatflow.client.model;

public class MessageRecord {
    
    private long timestamp;      
    private MessageType messageType;
    private long latencyMs;      
    private int statusCode;      
    private String roomId;
    
    public MessageRecord(long timestamp, MessageType messageType, 
                        long latencyMs, int statusCode, String roomId) {
        this.timestamp = timestamp;
        this.messageType = messageType;
        this.latencyMs = latencyMs;
        this.statusCode = statusCode;
        this.roomId = roomId;
    }
    
    // Getters
    public long getTimestamp() { return timestamp; }
    public MessageType getMessageType() { return messageType; }
    public long getLatencyMs() { return latencyMs; }
    public int getStatusCode() { return statusCode; }
    public String getRoomId() { return roomId; }
}