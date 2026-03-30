package com.chatflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages WebSocket connections, active users, and message processing statistics.
 * Maintains thread-safe mappings of rooms to sessions and session to user info.
 */
@Service
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    // roomId -> (sessionId -> WebSocketSession)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>
            roomSessions = new ConcurrentHashMap<>();

    // sessionId -> UserInfo
    private final ConcurrentHashMap<String, UserInfo> activeUsers = new ConcurrentHashMap<>();

    private final AtomicInteger totalConnections     = new AtomicInteger(0);
    private final AtomicInteger totalMessagesProcessed = new AtomicInteger(0);

    // -------------------------
    // Session Management
    // -------------------------

    /**
     * Adds a new WebSocket session to a chat room and registers the user.
     *
     * @param roomId  the room the session is joining
     * @param session the WebSocket session
     */
    public void addSession(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), session);

        activeUsers.put(session.getId(), new UserInfo(session.getId(), roomId));
        totalConnections.incrementAndGet();

        logger.info("Connection added | Room: {} | SessionId: {} | Total: {}",
                roomId, session.getId(), totalConnections.get());
    }

    /**
     * Removes a WebSocket session from a chat room and deregisters the user.
     *
     * @param roomId  the room the session is leaving
     * @param session the WebSocket session
     */
    public void removeSession(String roomId, WebSocketSession session) {
        ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session.getId());
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }

        activeUsers.remove(session.getId());
        totalConnections.decrementAndGet();

        logger.info("Connection removed | Room: {} | SessionId: {} | Total: {}",
                roomId, session.getId(), totalConnections.get());
    }

    // -------------------------
    // Getters
    // -------------------------

    /**
     * Returns all WebSocket sessions for a specific chat room.
     *
     * @param roomId the room ID
     * @return map of sessionId to WebSocketSession, or null if room is empty
     */
    public ConcurrentHashMap<String, WebSocketSession> getRoomSessions(String roomId) {
        return roomSessions.get(roomId);
    }

    /**
     * Returns the active user info map for monitoring purposes.
     *
     * @return map of sessionId to UserInfo
     */
    public ConcurrentHashMap<String, UserInfo> getActiveUsers() {
        return activeUsers;
    }

    /**
     * Increments the count of total messages processed.
     */
    public void incrementMessagesProcessed() {
        totalMessagesProcessed.incrementAndGet();
    }

    public int getTotalConnections() {
        return totalConnections.get();
    }

    public int getRoomCount() {
        return roomSessions.size();
    }

    public int getTotalMessagesProcessed() {
        return totalMessagesProcessed.get();
    }

    public int getActiveUserCount() {
        return activeUsers.size();
    }

    // -------------------------
    // UserInfo
    // -------------------------

    /**
     * Represents an active user's session metadata.
     * Stored in activeUsers map for session tracking and monitoring.
     */
    public static class UserInfo {

        private final String sessionId;
        private final String roomId;
        private final long connectedAt;

        public UserInfo(String sessionId, String roomId) {
            this.sessionId   = sessionId;
            this.roomId      = roomId;
            this.connectedAt = System.currentTimeMillis();
        }

        public String getSessionId()  { return sessionId; }
        public String getRoomId()     { return roomId; }
        public long getConnectedAt()  { return connectedAt; }
    }
}