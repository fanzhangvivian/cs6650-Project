package com.chatflow.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * DTO for deserializing messages received from RabbitMQ.
 * Mirrors the JSON structure published by server-v2's MessagePublisher.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) ensures forward compatibility:
 * if server-v2 adds new fields, consumer-v3 won't crash.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueMessage {

    @JsonProperty("messageId")
    private String messageId;

    @JsonProperty("roomId")
    private String roomId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("username")
    private String username;

    @JsonProperty("message")
    private String message;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("messageType")
    private String messageType;

    @JsonProperty("serverId")
    private String serverId;

    @JsonProperty("clientIp")
    private String clientIp;

    @JsonProperty("publishedAt")
    private Instant publishedAt;

    public QueueMessage() {}

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getMessageId()   { return messageId; }
    public String  getRoomId()      { return roomId; }
    public String  getUserId()      { return userId; }
    public String  getUsername()    { return username; }
    public String  getMessage()     { return message; }
    public String  getTimestamp()   { return timestamp; }
    public String  getMessageType() { return messageType; }
    public String  getServerId()    { return serverId; }
    public String  getClientIp()    { return clientIp; }
    public Instant getPublishedAt() { return publishedAt; }

    // ── Setters (required for stable Jackson deserialization) ─────────────────

    public void setMessageId(String messageId)     { this.messageId = messageId; }
    public void setRoomId(String roomId)           { this.roomId = roomId; }
    public void setUserId(String userId)           { this.userId = userId; }
    public void setUsername(String username)       { this.username = username; }
    public void setMessage(String message)         { this.message = message; }
    public void setTimestamp(String timestamp)     { this.timestamp = timestamp; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public void setServerId(String serverId)       { this.serverId = serverId; }
    public void setClientIp(String clientIp)       { this.clientIp = clientIp; }
    public void setPublishedAt(Instant publishedAt){ this.publishedAt = publishedAt; }
}