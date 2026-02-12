package com.chatflow.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConnectionManager class manages WebSocket connections for the chat application.
 * It maintains a mapping of chat rooms to their active WebSocket sessions and provides
 * methods to add/remove sessions, track total connections, and count messages processed.
 */
@Service
public class ConnectionManager {
    
    // roomId -> (sessionId -> WebSocketSession)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>> 
            roomSessions = new ConcurrentHashMap<>();
    
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger totalMessagesProcessed = new AtomicInteger(0);
    
    /**
     * Add a new WebSocket session to a chat room
     * @param roomId
     * @param session
     */
    public void addSession(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
                    .put(session.getId(), session);
        totalConnections.incrementAndGet();
        
        System.out.println("✅ Connection added | Room: " + roomId + 
                         " | SessionId: " + session.getId() + 
                         " | Total: " + totalConnections.get());
    }
    
    /**
     * Remove a WebSocket session from a chat room
     * @param roomId
     * @param session
     */
    public void removeSession(String roomId, WebSocketSession session) {
        ConcurrentHashMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session.getId());
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
        totalConnections.decrementAndGet();
        
        System.out.println("❌ Connection removed | Room: " + roomId + 
                         " | SessionId: " + session.getId() + 
                         " | Total: " + totalConnections.get());
    }
    
    /**
     * Get all WebSocket sessions for a specific chat room
     * @param roomId
     * @return
     */
    public ConcurrentHashMap<String, WebSocketSession> getRoomSessions(String roomId) {
        return roomSessions.get(roomId);
    }
    
    /**
     * Increment the count of total messages processed
     */
    public void incrementMessagesProcessed() {
        totalMessagesProcessed.incrementAndGet();
    }
    
    /**
     * Get total active connections across all rooms
     * @return
     */
    public int getTotalConnections() {
        return totalConnections.get();
    }
    
    public int getRoomCount() {
        return roomSessions.size();
    }
    
    public int getTotalMessagesProcessed() {
        return totalMessagesProcessed.get();
    }
    
    /**
     * Print current server statistics
     */
    public void printStats() {
        System.out.println("\n========== Server Statistics ==========");
        System.out.println("Active Connections: " + totalConnections.get());
        System.out.println("Active Rooms: " + roomSessions.size());
        System.out.println("Messages Processed: " + totalMessagesProcessed.get());
        System.out.println("======================================\n");
    }
}