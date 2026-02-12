package com.chatflow.handler;

import com.chatflow.model.ChatMessage;
import com.chatflow.model.ErrorResponse;
import com.chatflow.model.ServerResponse;
import com.chatflow.service.ConnectionManager;
import com.chatflow.validator.MessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * ChatWebSocketHandler class is the main WebSocket handler
 * Core business logic: receiving messages, validation, response
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;
    
    public ChatWebSocketHandler(ConnectionManager connectionManager) {
        this.objectMapper = new ObjectMapper();
        this.connectionManager = connectionManager;
    }
    
    /**
     * Called when a new WebSocket connection is established
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = extractRoomId(session);
        connectionManager.addSession(roomId, session);
        
        System.out.println("🔗 WebSocket connected | Room: " + roomId + 
                         " | Session: " + session.getId());
    }
    
    /**
     * Called when a text message is received (core method)
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) 
            throws Exception {
        
        String roomId = extractRoomId(session);
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. Parse JSON message
            ChatMessage chatMessage = objectMapper.readValue(
                    message.getPayload(), ChatMessage.class);
            
            System.out.println("📨 Received message | Room: " + roomId + 
                             " | From: " + chatMessage.getUsername() + 
                             " | Type: " + chatMessage.getMessageType());
            
            // 2. Validate message
            List<String> validationErrors = MessageValidator.validate(chatMessage);
            
            if (!validationErrors.isEmpty()) {
                // Validation failed - send error response
                sendErrorResponse(session, validationErrors);
                System.err.println("❌ Validation failed: " + validationErrors);
                return;
            }
            
            // 3. Build success response (Echo back)
            ServerResponse response = new ServerResponse(
                    chatMessage,
                    Instant.now().toString(),
                    "SUCCESS",
                    roomId
            );
            
            // 4. Send response
            String jsonResponse = objectMapper.writeValueAsString(response);
            session.sendMessage(new TextMessage(jsonResponse));
            
            // 5. Record statistics
            connectionManager.incrementMessagesProcessed();
            
            long processingTime = System.currentTimeMillis() - startTime;
            System.out.println("Message processed in " + processingTime + "ms");
            
        } catch (Exception e) {
            // JSON parsing error or other exceptions
            System.err.println("💥 Error processing message: " + e.getMessage());
            e.printStackTrace();
            
            sendErrorResponse(session, 
                    Collections.singletonList("Invalid JSON format: " + e.getMessage()));
        }
    }
    
    /**
     * Called when a WebSocket connection is closed
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) 
            throws Exception {
        
        String roomId = extractRoomId(session);
        connectionManager.removeSession(roomId, session);
        
        System.out.println("🔌 WebSocket disconnected | Room: " + roomId + 
                         " | Session: " + session.getId() + 
                         " | Status: " + status);
    }
    
    /**
     * Called when a transport error occurs
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) 
            throws Exception {
        
        System.err.println("⚠️ Transport error | Session: " + session.getId());
        exception.printStackTrace();
        
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
    
    /**
     * Extract roomId from session URI
     * URI format: ws://host:port/chat/{roomId}
     */
    private String extractRoomId(WebSocketSession session) {
        String path = session.getUri().getPath();
        // /chat/room123 -> room123
        return path.substring(path.lastIndexOf('/') + 1);
    }
    
    /**
     * Send error response
     */
    private void sendErrorResponse(WebSocketSession session, List<String> errors) 
            throws Exception {
        
        ErrorResponse errorResponse = new ErrorResponse(
                "ERROR",
                errors,
                Instant.now().toString()
        );
        
        String jsonError = objectMapper.writeValueAsString(errorResponse);
        session.sendMessage(new TextMessage(jsonError));
    }
}