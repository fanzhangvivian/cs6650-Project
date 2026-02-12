package com.chatflow.client.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Chat message entity
 */
public class ChatMessage {
    
    @JsonProperty("userId")
    private String userId;
    
    @JsonProperty("username")
    private String username;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("timestamp")
    private String timestamp;
    
    @JsonProperty("messageType")
    private MessageType messageType;
    
    // Client-side only fields (not sent to server)
    @JsonIgnore
    private String roomId;
    
    @JsonIgnore
    private long clientSendTimestamp;
    
    // Constructors
    public ChatMessage() {}
    
    public ChatMessage(String userId, String username, String message, 
                      String timestamp, MessageType messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }
    
    // Getters and Setters
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    
    public long getClientSendTimestamp() { return clientSendTimestamp; }
    public void setClientSendTimestamp(long clientSendTimestamp) { 
        this.clientSendTimestamp = clientSendTimestamp; 
    }
}
