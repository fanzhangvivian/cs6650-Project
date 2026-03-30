package com.chatflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a message published to the RabbitMQ queue.
 * Extends the basic chat message with routing and tracing metadata.
 */
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

    // -------------------------
    // Constructors
    // -------------------------

    public QueueMessage() {}

    /**
     * Builds a QueueMessage from an incoming ChatMessage.
     * Automatically generates messageId and publishedAt timestamp.
     *
     * @param chatMessage the validated incoming chat message
     * @param roomId      the room extracted from the WebSocket path
     * @param serverId    identifier of the server instance handling this message
     * @param clientIp    IP address of the connected client
     */
    public static QueueMessage from(ChatMessage chatMessage,
                                    String roomId,
                                    String serverId,
                                    String clientIp) {
        QueueMessage qm = new QueueMessage();
        qm.messageId   = UUID.randomUUID().toString();
        qm.roomId      = roomId;
        qm.userId      = chatMessage.getUserId();
        qm.username    = chatMessage.getUsername();
        qm.message     = chatMessage.getMessage();
        qm.timestamp   = chatMessage.getTimestamp();
        qm.messageType = chatMessage.getMessageType().name();
        qm.serverId    = serverId;
        qm.clientIp    = clientIp;
        qm.publishedAt = Instant.now();
        return qm;
    }

    // -------------------------
    // Getters and Setters
    // -------------------------

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}