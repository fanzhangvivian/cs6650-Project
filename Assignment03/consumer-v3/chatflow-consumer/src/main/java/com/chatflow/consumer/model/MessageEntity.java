package com.chatflow.consumer.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA Entity mapping to the 'messages' table in PostgreSQL.
 *
 * This is the consumer-v3 AUTHORITATIVE version.
 * It contains the from(QueueMessage) factory method for write operations.
 *
 * The server-v2 version is query-only and does NOT contain from().
 *
 * IMPORTANT: If schema.sql changes, update BOTH this file
 * and server-v2's MessageEntity to stay in sync.
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", unique = true, nullable = false, length = 36)
    private String messageId;

    @Column(name = "room_id", nullable = false, length = 20)
    private String roomId;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "username", nullable = false, length = 20)
    private String username;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    // Renamed from 'timestamp' to avoid confusion with PostgreSQL type name
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "message_type", nullable = false, length = 10)
    private String messageType;

    @Column(name = "server_id", length = 50)
    private String serverId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "published_at")
    private Instant publishedAt;

    // Records when consumer accepted message into persistence pipeline.
    // NOT the DB commit time. Used to approximate end-to-end ingestion latency:
    //   latency ≈ received_at - event_time
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // ── Factory method (consumer-v3 only) ─────────────────────────────────────

    /**
     * Converts a deserialized QueueMessage into a MessageEntity for persistence.
     *
     * Type conversions performed here:
     *   timestamp (ISO-8601 String) → eventTime (Instant)
     *   receivedAt = Instant.now() at moment of Entity creation
     *
     * @param qm the deserialized RabbitMQ message
     * @return a MessageEntity ready for saveAll() or upsert()
     */
    public static MessageEntity from(QueueMessage qm) {
        MessageEntity e = new MessageEntity();
        e.messageId   = qm.getMessageId();
        e.roomId      = qm.getRoomId();
        e.userId      = qm.getUserId();
        e.username    = qm.getUsername();
        e.message     = qm.getMessage();
        e.eventTime   = Instant.parse(qm.getTimestamp()); // String → Instant
        e.messageType = qm.getMessageType();
        e.serverId    = qm.getServerId();
        e.clientIp    = qm.getClientIp();
        e.publishedAt = qm.getPublishedAt();
        e.receivedAt  = Instant.now(); // pipeline entry time
        return e;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long    getId()          { return id; }
    public String  getMessageId()   { return messageId; }
    public String  getRoomId()      { return roomId; }
    public String  getUserId()      { return userId; }
    public String  getUsername()    { return username; }
    public String  getMessage()     { return message; }
    public Instant getEventTime()   { return eventTime; }
    public String  getMessageType() { return messageType; }
    public String  getServerId()    { return serverId; }
    public String  getClientIp()    { return clientIp; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getReceivedAt()  { return receivedAt; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setId(Long id)                  { this.id = id; }
    public void setMessageId(String messageId)  { this.messageId = messageId; }
    public void setRoomId(String roomId)        { this.roomId = roomId; }
    public void setUserId(String userId)        { this.userId = userId; }
    public void setUsername(String username)    { this.username = username; }
    public void setMessage(String message)      { this.message = message; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public void setMessageType(String t)        { this.messageType = t; }
    public void setServerId(String serverId)    { this.serverId = serverId; }
    public void setClientIp(String clientIp)    { this.clientIp = clientIp; }
    public void setPublishedAt(Instant t)       { this.publishedAt = t; }
    public void setReceivedAt(Instant t)        { this.receivedAt = t; }
}