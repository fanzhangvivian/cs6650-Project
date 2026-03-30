package com.chatflow.consumer.service;

import com.chatflow.consumer.config.RabbitMQConsumerConfig;
import com.chatflow.consumer.model.MessageEntity;
import com.chatflow.consumer.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages consumer worker threads that pull messages from RabbitMQ queues,
 * broadcast them to server-v2 instances, and persist them to PostgreSQL.
 *
 * Key design decisions:
 *
 * 1. NON-BLOCKING DeliverCallback:
 *    Submits broadcast + DB offer to broadcastExecutor immediately.
 *    RabbitMQ dispatch threads are never blocked by HTTP or DB I/O.
 *
 * 2. ACK ordering:
 *    broadcast success → offer to DB buffer success → basicAck
 *    If offer fails (buffer full) → basicNack requeue
 *    Nack-requeue may cause duplicate broadcasts, but DB-level
 *    ON CONFLICT DO NOTHING prevents duplicate persistence.
 *
 * 3. Real DLQ:
 *    chat.dlq declared on startup. Original message body published
 *    to DLQ when nack itself fails (last resort recovery path).
 *
 * 4. Malformed messages:
 *    Deserialization failures are acked (to prevent infinite loop)
 *    and counted separately in malformedMessages, NOT in messagesConsumed.
 */
@DependsOn("rabbitMQConsumerConfig")
@Service
public class MessageConsumerService {

    private static final Logger logger =
            LoggerFactory.getLogger(MessageConsumerService.class);

    private static final String DLQ_QUEUE = "chat.dlq";

    private final RabbitMQConsumerConfig config;
    private final ServerHttpClient httpClient;
    private final DatabaseWriterService databaseWriterService;
    private final ObjectMapper objectMapper;

    @Value("${rabbitmq.queue-count}")
    private int queueCount;

    @Value("${rabbitmq.consumer-thread-count}")
    private int consumerThreadCount;

    @Value("${rabbitmq.consumer-prefetch}")
    private int prefetch;

    private final List<Thread> workers = new ArrayList<>();
    private ExecutorService broadcastExecutor;

    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_IDS = 10000;

    // ── Metrics ───────────────────────────────────────────────────────────────
    private final AtomicLong messagesConsumed  = new AtomicLong(); // broadcast + buffer offer succeeded
    private final AtomicLong messagesFailed    = new AtomicLong(); // broadcast failed or buffer full nack
    private final AtomicLong messagesBuffered  = new AtomicLong(); // successfully offered to DB buffer
    private final AtomicLong bufferFullNacks   = new AtomicLong(); // nacked due to DB buffer full
    private final AtomicLong malformedMessages = new AtomicLong(); // deserialization failed, acked to avoid loop

    public MessageConsumerService(RabbitMQConsumerConfig config,
                                  ServerHttpClient httpClient,
                                  DatabaseWriterService databaseWriterService) {
        this.config                = config;
        this.httpClient            = httpClient;
        this.databaseWriterService = databaseWriterService;
        this.objectMapper          = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        broadcastExecutor = Executors.newFixedThreadPool(consumerThreadCount * 2);
        declareDlq();

        List<List<String>> assignments = assignQueues();
        for (int i = 0; i < consumerThreadCount; i++) {
            final List<String> queuesForWorker = assignments.get(i);
            Thread worker = new Thread(() -> runWorker(queuesForWorker));
            worker.setName("consumer-worker-" + i);
            worker.start();
            workers.add(worker);
        }

        logger.info("Started {} consumer workers, {} queues, {} broadcast threads, DLQ={}",
                consumerThreadCount, queueCount, consumerThreadCount * 2, DLQ_QUEUE);
    }

    private void declareDlq() {
        try {
            Channel channel = config.getConnection().createChannel();
            channel.queueDeclare(DLQ_QUEUE, true, false, false, null);
            channel.close();
            logger.info("DLQ declared: {}", DLQ_QUEUE);
        } catch (Exception e) {
            logger.error("Failed to declare DLQ {}", DLQ_QUEUE, e);
        }
    }

    private List<List<String>> assignQueues() {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < consumerThreadCount; i++) {
            result.add(new ArrayList<>());
        }
        for (int i = 1; i <= queueCount; i++) {
            result.get((i - 1) % consumerThreadCount).add("room." + i);
        }
        return result;
    }

    private void runWorker(List<String> queues) {
        while (!Thread.currentThread().isInterrupted()) {
            Channel channel = null;
            try {
                channel = config.getConnection().createChannel();
                channel.basicQos(prefetch);
                final Channel finalChannel = channel;

                for (String queue : queues) {
                    final String queueName = queue;

                    finalChannel.basicConsume(queueName, false,
                        (tag, delivery) -> {
                            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                            byte[] body      = delivery.getBody();
                            String bodyStr   = new String(body, StandardCharsets.UTF_8);

                            // [改动3] extractMessageId 用 ObjectMapper，不是手写截取
                            String messageId = extractMessageId(bodyStr);

                            // In-memory dedup (fast path only)
                            // DB-level ON CONFLICT DO NOTHING is the durable guarantee
                            if (messageId != null && !processedIds.add(messageId)) {
                                try {
                                    finalChannel.basicAck(deliveryTag, false);
                                } catch (Exception e) {
                                    logger.warn("Ack failed for duplicate {}", messageId);
                                }
                                logger.debug("Duplicate skipped (in-memory): {}", messageId);
                                return;
                            }
                            if (processedIds.size() > MAX_PROCESSED_IDS) {
                                processedIds.clear();
                            }

                            broadcastExecutor.submit(() ->
                                handleMessage(finalChannel, deliveryTag,
                                        body, bodyStr, messageId, queueName));
                        },
                        tag -> logger.warn("Consumer cancelled for queue: {}", tag)
                    );

                    logger.info("Consumer registered for queue: {}", queueName);
                }

                while (!Thread.currentThread().isInterrupted() && finalChannel.isOpen()) {
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Worker interrupted, exiting");
                break;
            } catch (Exception e) {
                logger.error("Worker crashed, restarting in 3s. Queues={}", queues, e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (channel != null && channel.isOpen()) {
                    try { channel.close(); }
                    catch (Exception e) { logger.warn("Failed to close worker channel", e); }
                }
            }
        }
    }

    /**
     * Handles a single message in broadcastExecutor thread.
     *
     * ACK ordering:
     *   Step 1: broadcastParallel()          → fail: nack requeue
     *   Step 2: deserialize → MessageEntity  → fail: ack + malformedMessages++ (avoid loop)
     *   Step 3: databaseWriterService.offer()→ fail: nack requeue (buffer full)
     *   Step 4: basicAck                     → only if steps 1-3 all succeeded
     */
    private void handleMessage(Channel channel, long deliveryTag,
                               byte[] body, String bodyStr,
                               String messageId, String queueName) {
        try {
            // Step 1: Broadcast
            boolean broadcastSuccess = httpClient.broadcastParallel(body);
            if (!broadcastSuccess) {
                messagesFailed.incrementAndGet();
                // [改动2] pass body so DLQ has original payload if nack fails
                nackRequeue(channel, deliveryTag, body, messageId, "broadcast failed");
                return;
            }

            // Step 2: Deserialize
            MessageEntity entity;
            try {
                QueueMessage queueMessage = objectMapper.readValue(bodyStr, QueueMessage.class);
                entity = MessageEntity.from(queueMessage);
            } catch (Exception e) {
                // Malformed: ack to prevent infinite loop, count separately
                // [改动1] NOT counted in messagesConsumed
                logger.error("Deserialization failed for message {}, acking to prevent loop: {}",
                        messageId, e.getMessage());
                ack(channel, deliveryTag, messageId);
                malformedMessages.incrementAndGet();
                return;
            }

            // Step 3: Offer to DB buffer
            boolean offered = databaseWriterService.offer(entity);
            if (!offered) {
                bufferFullNacks.incrementAndGet();
                messagesFailed.incrementAndGet();
                // [改动2] pass body
                nackRequeue(channel, deliveryTag, body, messageId, "DB buffer full");
                return;
            }

            // Step 4: Ack — only after broadcast AND buffer offer both succeeded
            messagesBuffered.incrementAndGet();
            ack(channel, deliveryTag, messageId);
            messagesConsumed.incrementAndGet(); // [改动1] only true successes counted here

            logger.debug("Message {} processed: broadcast ✓, buffer ✓, acked ✓", messageId);

        } catch (Exception e) {
            messagesFailed.incrementAndGet();
            logger.error("Unexpected error handling message {} from {}", messageId, queueName, e);
            // [改动2] pass body
            nackRequeue(channel, deliveryTag, body, messageId, "unexpected: " + e.getMessage());
        }
    }

    // ── Ack / Nack helpers ────────────────────────────────────────────────────

    private void ack(Channel channel, long deliveryTag, String messageId) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            logger.error("basicAck failed for message {}", messageId, e);
        }
    }

    /**
     * [改动2] Now accepts byte[] body so DLQ receives original message payload.
     * If basicNack itself fails, publishes original body to DLQ as last resort.
     */
    private void nackRequeue(Channel channel, long deliveryTag,
                              byte[] body, String messageId, String reason) {
        try {
            channel.basicNack(deliveryTag, false, true);
            logger.warn("Message {} nacked for requeue: {}", messageId, reason);
        } catch (IOException e) {
            logger.error("basicNack failed for message {}, reason was: {}, publishing to DLQ",
                    messageId, reason, e);
            // Last resort: publish original body to DLQ
            publishToDlq(body);
        }
    }

    /**
     * Publishes original message body to chat.dlq.
     * Body contains full JSON payload, enabling message replay if needed.
     */
    private void publishToDlq(byte[] body) {
        try {
            Channel dlqChannel = config.getConnection().createChannel();
            dlqChannel.basicPublish(
                    "",
                    DLQ_QUEUE,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    body  // [改动2] original message body, not just messageId
            );
            dlqChannel.close();
            logger.info("Original message body published to DLQ: {}", DLQ_QUEUE);
        } catch (Exception e) {
            logger.error("Failed to publish to DLQ {}", DLQ_QUEUE, e);
        }
    }

    /**
     * [改动3] Uses ObjectMapper.readTree() instead of hand-written substring parsing.
     * More robust against JSON whitespace, field ordering, and escaping variations.
     */
    private String extractMessageId(String json) {
        try {
            return objectMapper.readTree(json).path("messageId").asText(null);
        } catch (Exception e) {
            logger.warn("Failed to extract messageId from JSON: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down MessageConsumerService...");
        broadcastExecutor.shutdown();
        try {
            broadcastExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        logger.info("MessageConsumerService shutdown. " +
                        "Consumed={}, Failed={}, Buffered={}, " +
                        "BufferFullNacks={}, Malformed={}",
                messagesConsumed.get(), messagesFailed.get(),
                messagesBuffered.get(), bufferFullNacks.get(),
                malformedMessages.get());
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public long getMessagesConsumed()    { return messagesConsumed.get(); }
    public long getMessagesFailed()      { return messagesFailed.get(); }
    public long getActiveConsumerCount() { return consumerThreadCount; }
    public long getMessagesBuffered()    { return messagesBuffered.get(); }
    public long getBufferFullNacks()     { return bufferFullNacks.get(); }
    public long getMalformedMessages()   { return malformedMessages.get(); }
}