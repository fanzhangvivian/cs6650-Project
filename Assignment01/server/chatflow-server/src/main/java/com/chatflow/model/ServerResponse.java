package com.chatflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ServerResponse {
    
    @JsonProperty("originalMessage")
    private ChatMessage originalMessage;
    
    @JsonProperty("serverTimestamp")
    private String serverTimestamp;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("roomId")
    private String roomId;
    
    public ServerResponse() {}
    
    public ServerResponse(ChatMessage originalMessage, String serverTimestamp, String status, String roomId) {
        this.originalMessage = originalMessage;
        this.serverTimestamp = serverTimestamp;
        this.status = status;
        this.roomId = roomId;
    }
    
    public ChatMessage getOriginalMessage() { return originalMessage; }
    public void setOriginalMessage(ChatMessage originalMessage) { this.originalMessage = originalMessage; }
    
    public String getServerTimestamp() { return serverTimestamp; }
    public void setServerTimestamp(String serverTimestamp) { this.serverTimestamp = serverTimestamp; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
}
