package com.chatflow.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity mapping to the 'messages' table in PostgreSQL.
 *
 * IMPORTANT: This class is the server-v2 (query-only) version.
 * It does NOT contain a from(QueueMessage) factory method.
 * The consumer-v3 version is the authoritative definition for writes.
 *
 * If schema.sql changes, this file MUST be updated to stay in sync.
 * Fields must match schema.sql exactly (column names, types, nullable).
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Maps to: message_id VARCHAR(36) UNIQUE NOT NULL
    @Column(name = "message_id", unique = true, nullable = false, length = 36)
    private String messageId;

    // Maps to: room_id VARCHAR(20) NOT NULL
    @Column(name = "room_id", nullable = false, length = 20)
    private String roomId;

    // Maps to: user_id VARCHAR(10) NOT NULL
    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    // Maps to: username VARCHAR(20) NOT NULL
    @Column(name = "username", nullable = false, length = 20)
    private String username;

    // Maps to: message TEXT NOT NULL
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    // Maps to: event_time TIMESTAMPTZ NOT NULL
    // Renamed from 'timestamp' to avoid confusion with PostgreSQL type name
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    // Maps to: message_type VARCHAR(10) NOT NULL
    @Column(name = "message_type", nullable = false, length = 10)
    private String messageType;

    // Maps to: server_id VARCHAR(50) (nullable)
    @Column(name = "server_id", length = 50)
    private String serverId;

    // Maps to: client_ip VARCHAR(45) (nullable)
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    // Maps to: published_at TIMESTAMPTZ (nullable)
    @Column(name = "published_at")
    private Instant publishedAt;

    // Maps to: received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    // Records when consumer accepted the message into the persistence pipeline.
    // Approximates end-to-end ingestion latency: received_at - event_time
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId()            { return id; }
    public String getMessageId()   { return messageId; }
    public String getRoomId()      { return roomId; }
    public String getUserId()      { return userId; }
    public String getUsername()    { return username; }
    public String getMessage()     { return message; }
    public Instant getEventTime()  { return eventTime; }
    public String getMessageType() { return messageType; }
    public String getServerId()    { return serverId; }
    public String getClientIp()    { return clientIp; }
    public Instant getPublishedAt(){ return publishedAt; }
    public Instant getReceivedAt() { return receivedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setId(Long id)                   { this.id = id; }
    public void setMessageId(String messageId)   { this.messageId = messageId; }
    public void setRoomId(String roomId)         { this.roomId = roomId; }
    public void setUserId(String userId)         { this.userId = userId; }
    public void setUsername(String username)     { this.username = username; }
    public void setMessage(String message)       { this.message = message; }
    public void setEventTime(Instant eventTime)  { this.eventTime = eventTime; }
    public void setMessageType(String t)         { this.messageType = t; }
    public void setServerId(String serverId)     { this.serverId = serverId; }
    public void setClientIp(String clientIp)     { this.clientIp = clientIp; }
    public void setPublishedAt(Instant t)        { this.publishedAt = t; }
    public void setReceivedAt(Instant t)         { this.receivedAt = t; }
}