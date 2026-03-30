package com.chatflow.service;

import com.chatflow.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes validated chat messages to RabbitMQ.
 * Coordinates with ChannelPool for channel management and
 * CircuitBreaker for failure protection.
 */
@Service
public class MessagePublisher {

    private static final Logger logger = LoggerFactory.getLogger(MessagePublisher.class);

    private final ChannelPool channelPool;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    // -------------------------
    // Metrics
    // -------------------------

    private final AtomicLong publishSuccess  = new AtomicLong(0);
    private final AtomicLong publishFailures = new AtomicLong(0);

    // -------------------------
    // Constructor
    // -------------------------

    public MessagePublisher(ChannelPool channelPool, CircuitBreaker circuitBreaker) {
        this.channelPool    = channelPool;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper   = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    // -------------------------
    // Core Publish
    // -------------------------

    /**
     * Publishes a QueueMessage to the RabbitMQ exchange.
     * Routing key is derived from the message's roomId: room.{roomId}
     *
     * Flow:
     *   1. Check circuit breaker - reject immediately if OPEN
     *   2. Borrow a channel from the pool
     *   3. Serialize message to JSON
     *   4. Publish with persistent delivery mode
     *   5. Record success or failure and return channel to pool
     *
     * @param queueMessage the message to publish
     * @return true if published successfully, false otherwise
     */
    public boolean publish(QueueMessage queueMessage) {

        // Step 1: Check circuit breaker
        if (!circuitBreaker.isAllowed()) {
            logger.warn("CircuitBreaker is OPEN, rejecting publish for room {}",
                queueMessage.getRoomId());
            publishFailures.incrementAndGet();
            return false;
        }

        // Step 2: Borrow a channel from the pool
        Channel channel = channelPool.borrowChannel();
        if (channel == null) {
            logger.error("No channel available from pool, publish failed for room {}",
                queueMessage.getRoomId());
            circuitBreaker.recordFailure();
            publishFailures.incrementAndGet();
            return false;
        }

        // Step 3 & 4: Serialize and publish
        try {
            String routingKey = "room." + queueMessage.getRoomId();
            byte[] body       = objectMapper.writeValueAsBytes(queueMessage);

            // PERSISTENT_TEXT_PLAIN sets delivery mode = 2 (persistent)
            // This ensures messages survive RabbitMQ restart (third layer of durability)
            channel.basicPublish(
                exchange,
                routingKey,
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                body
            );

            // Step 5a: Record success
            circuitBreaker.recordSuccess();
            publishSuccess.incrementAndGet();

            logger.debug("Published message {} to {}", queueMessage.getMessageId(), routingKey);
            return true;

        } catch (Exception e) {
            // Step 5b: Record failure
            logger.error("Failed to publish message {} to room {}",
                queueMessage.getMessageId(), queueMessage.getRoomId(), e);
            circuitBreaker.recordFailure();
            publishFailures.incrementAndGet();
            return false;

        } finally {
            // Always return channel to pool regardless of success or failure.
            // returnChannel() internally checks isOpen() - if channel is broken,
            // it discards it and replenishes the pool with a new one.
            channelPool.returnChannel(channel);
        }
    }

    // -------------------------
    // Metrics
    // -------------------------

    /**
     * Returns the total number of successfully published messages.
     *
     * @return success count
     */
    public long getPublishSuccess() {
        return publishSuccess.get();
    }

    /**
     * Returns the total number of failed publish attempts.
     *
     * @return failure count
     */
    public long getPublishFailures() {
        return publishFailures.get();
    }
}