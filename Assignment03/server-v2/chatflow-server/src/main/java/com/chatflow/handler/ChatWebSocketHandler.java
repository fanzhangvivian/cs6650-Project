package com.chatflow.handler;

import com.chatflow.model.ChatMessage;
import com.chatflow.model.ErrorResponse;
import com.chatflow.model.QueueMessage;
import com.chatflow.model.ServerResponse;
import com.chatflow.service.ConnectionManager;
import com.chatflow.service.MessagePublisher;
import com.chatflow.validator.MessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * ChatWebSocketHandler handles incoming WebSocket connections and messages.
 * In server-v2, validated messages are published to RabbitMQ instead of being echoed back.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;
    private final MessagePublisher messagePublisher;

    @Value("${server.id:server-1}")
    private String serverId;

    public ChatWebSocketHandler(ConnectionManager connectionManager,
                                MessagePublisher messagePublisher) {
        this.objectMapper     = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.connectionManager = connectionManager;
        this.messagePublisher  = messagePublisher;
    }

    // -------------------------
    // Connection Lifecycle
    // -------------------------

    /**
     * Called when a new WebSocket connection is established.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = extractRoomId(session);
        connectionManager.addSession(roomId, session);
        logger.info("WebSocket connected | Room: {} | Session: {}", roomId, session.getId());
    }

    /**
     * Called when a WebSocket connection is closed.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = extractRoomId(session);
        connectionManager.removeSession(roomId, session);
        logger.info("WebSocket disconnected | Room: {} | Session: {} | Status: {}",
            roomId, session.getId(), status);
    }

    /**
     * Called when a transport error occurs.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Transport error | Session: {}", session.getId(), exception);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    // -------------------------
    // Message Handling
    // -------------------------

    /**
     * Called when a text message is received.
     *
     * Flow:
     *   1. Parse JSON into ChatMessage
     *   2. Validate message fields
     *   3. Build QueueMessage with routing metadata
     *   4. Publish to RabbitMQ via MessagePublisher
     *   5. Return SUCCESS or ERROR response to client
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws Exception {

        String roomId   = extractRoomId(session);
        long startTime  = System.currentTimeMillis();

        try {
            // Step 1: Parse JSON
            ChatMessage chatMessage = objectMapper.readValue(
                message.getPayload(), ChatMessage.class);

            logger.debug("Received message | Room: {} | From: {} | Type: {}",
                roomId, chatMessage.getUsername(), chatMessage.getMessageType());

            // Step 2: Validate
            List<String> validationErrors = MessageValidator.validate(chatMessage);
            if (!validationErrors.isEmpty()) {
                sendErrorResponse(session, validationErrors);
                logger.warn("Validation failed | Room: {} | Errors: {}", roomId, validationErrors);
                return;
            }

            // Step 3: Build QueueMessage
            String clientIp = extractClientIp(session);
            QueueMessage queueMessage = QueueMessage.from(chatMessage, roomId, serverId, clientIp);

            // Step 4: Publish to RabbitMQ
            boolean published = messagePublisher.publish(queueMessage);

            // Step 5: Respond to client
            if (published) {
                ServerResponse response = new ServerResponse(
                    chatMessage,
                    Instant.now().toString(),
                    "SUCCESS",
                    roomId
                );
                String jsonResponse = objectMapper.writeValueAsString(response);
                session.sendMessage(new TextMessage(jsonResponse));

                connectionManager.incrementMessagesProcessed();

                long processingTime = System.currentTimeMillis() - startTime;
                logger.debug("Message published successfully in {}ms | Room: {}", processingTime, roomId);

            } else {
                // Publish failed - circuit breaker open or queue unavailable
                long processingTime = System.currentTimeMillis() - startTime;
                sendErrorResponse(session,
                    Collections.singletonList("Message could not be delivered, please try again"));
                logger.warn("Publish failed in {}ms | Room: {} | MessageId: {}",
                    processingTime, roomId, queueMessage.getMessageId());
            }

        } catch (Exception e) {
            logger.error("Error processing message | Room: {} | Error: {}", roomId, e.getMessage(), e);
            sendErrorResponse(session,
                Collections.singletonList("Invalid JSON format: " + e.getMessage()));
        }
    }

    // -------------------------
    // Private Helpers
    // -------------------------

    /**
     * Extracts the roomId from the WebSocket session URI.
     * URI format: ws://host:port/chat/{roomId}
     *
     * @param session the active WebSocket session
     * @return the roomId string
     */
    private String extractRoomId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Extracts the client IP address from the WebSocket session.
     * Falls back to "unknown" if the remote address is unavailable.
     *
     * @param session the active WebSocket session
     * @return the client IP address string
     */
    private String extractClientIp(WebSocketSession session) {
        try {
            return session.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Sends an error response to the client.
     *
     * @param session the active WebSocket session
     * @param errors  list of error messages
     */
    private void sendErrorResponse(WebSocketSession session, List<String> errors) throws Exception {
        ErrorResponse errorResponse = new ErrorResponse(
            "ERROR",
            errors,
            Instant.now().toString()
        );
        String jsonError = objectMapper.writeValueAsString(errorResponse);
        session.sendMessage(new TextMessage(jsonError));
    }
}