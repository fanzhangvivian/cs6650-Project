package com.chatflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChatMessage {
    
    @NotNull(message = "userId is required")
    @JsonProperty("userId")
    private String userId;
    
    @NotNull(message = "username is required")
    @Size(min = 3, max = 20, message = "username must be 3-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "username must be alphanumeric")
    @JsonProperty("username")
    private String username;
    
    @NotNull(message = "message is required")
    @Size(min = 1, max = 500, message = "message must be 1-500 characters")
    @JsonProperty("message")
    private String message;
    
    @NotNull(message = "timestamp is required")
    @JsonProperty("timestamp")
    private String timestamp;
    
    @NotNull(message = "messageType is required")
    @JsonProperty("messageType")
    private MessageType messageType;
    
    public ChatMessage() {}
    
    public ChatMessage(String userId, String username, String message, String timestamp, MessageType messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }
    
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
}
