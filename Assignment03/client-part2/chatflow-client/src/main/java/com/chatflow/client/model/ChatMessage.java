// package com.chatflow.client.model;

// import com.fasterxml.jackson.annotation.JsonIgnore;
// import com.fasterxml.jackson.annotation.JsonProperty;

// /**
//  * Chat message entity
//  */
// public class ChatMessage {
    
//     @JsonProperty("userId")
//     private String userId;
    
//     @JsonProperty("username")
//     private String username;
    
//     @JsonProperty("message")
//     private String message;
    
//     @JsonProperty("timestamp")
//     private String timestamp;
    
//     @JsonProperty("messageType")
//     private MessageType messageType;
    
//     // Client-side only fields (not sent to server)
//     @JsonIgnore
//     private String roomId;
    
//     @JsonIgnore
//     private long clientSendTimestamp;
    
//     // Constructors
//     public ChatMessage() {}
    
//     public ChatMessage(String userId, String username, String message, 
//                       String timestamp, MessageType messageType) {
//         this.userId = userId;
//         this.username = username;
//         this.message = message;
//         this.timestamp = timestamp;
//         this.messageType = messageType;
//     }
    
//     // Getters and Setters
//     public String getUserId() { return userId; }
//     public void setUserId(String userId) { this.userId = userId; }
    
//     public String getUsername() { return username; }
//     public void setUsername(String username) { this.username = username; }
    
//     public String getMessage() { return message; }
//     public void setMessage(String message) { this.message = message; }
    
//     public String getTimestamp() { return timestamp; }
//     public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
//     public MessageType getMessageType() { return messageType; }
//     public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    
//     public String getRoomId() { return roomId; }
//     public void setRoomId(String roomId) { this.roomId = roomId; }
    
//     public long getClientSendTimestamp() { return clientSendTimestamp; }
//     public void setClientSendTimestamp(long clientSendTimestamp) { 
//         this.clientSendTimestamp = clientSendTimestamp; 
//     }
// }


package com.chatflow.client.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    private String messageId;

    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private MessageType messageType;

    private String roomId;

    public ChatMessage() {}

    // Restore your existing constructor (the one MessageGenerator uses)
    public ChatMessage(String userId,
                       String username,
                       String message,
                       String timestamp,
                       MessageType messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }

    // Optional convenience ctor if you ever want to set roomId too
    public ChatMessage(String userId,
                       String username,
                       String message,
                       String timestamp,
                       MessageType messageType,
                       String roomId) {
        this(userId, username, message, timestamp, messageType);
        this.roomId = roomId;
    }

    // ===== getters/setters =====
    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
}