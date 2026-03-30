package com.chatflow.controller;

import com.chatflow.model.QueueMessage;
import com.chatflow.service.RoomBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal HTTP endpoint for receiving broadcast requests from the Consumer application.
 * Accepts a QueueMessage and broadcasts it to all WebSocket sessions in the room.
 * This endpoint is internal and should not be exposed to external clients.
 */
@RestController
@RequestMapping("/internal")
public class BroadcastController {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastController.class);

    private final RoomBroadcaster roomBroadcaster;

    public BroadcastController(RoomBroadcaster roomBroadcaster) {
        this.roomBroadcaster = roomBroadcaster;
    }

    /**
     * Receives a QueueMessage from the Consumer application and broadcasts
     * it to all WebSocket sessions in the specified room.
     *
     * @param queueMessage the message to broadcast
     * @return response with delivered and failed counts
     */
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(@RequestBody QueueMessage queueMessage) {
        logger.debug("Received broadcast request for room {} message {}",
                queueMessage.getRoomId(), queueMessage.getMessageId());

        RoomBroadcaster.BroadcastResult result = roomBroadcaster.broadcast(queueMessage);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("roomId", queueMessage.getRoomId());
        response.put("messageId", queueMessage.getMessageId());
        response.put("delivered", result.getDelivered());
        response.put("failed", result.getFailed());

        logger.debug("Broadcast complete | Room: {} | Delivered: {} | Failed: {}",
                queueMessage.getRoomId(), result.getDelivered(), result.getFailed());

        return ResponseEntity.ok(response);
    }
}