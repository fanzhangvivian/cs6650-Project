package com.chatflow.consumer.service;

import com.chatflow.consumer.config.RabbitMQConsumerConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages consumer worker threads that pull messages from RabbitMQ queues
 * and broadcast them to all server-v2 instances via ServerHttpClient.
 *
 * Each worker thread owns its own Channel and handles multiple queues.
 * Channel and thread are fully bound, ensuring thread safety for basicAck calls.
 * Supports configurable thread count for Part 4 performance tuning.
 */
@Service
public class MessageConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(MessageConsumerService.class);

    private final RabbitMQConsumerConfig config;
    private final ServerHttpClient httpClient;

    @Value("${rabbitmq.queue-count}")
    private int queueCount;

    @Value("${rabbitmq.consumer-thread-count}")
    private int consumerThreadCount;

    @Value("${rabbitmq.consumer-prefetch}")
    private int prefetch;

    private final List<Thread> workers = new ArrayList<>();

    // Deduplication set - tracks recently processed messageIds
    // Cleared when size exceeds limit to prevent unbounded memory growth
    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_IDS = 10000;

    // -------------------------
    // Metrics
    // -------------------------

    private final AtomicLong messagesConsumed = new AtomicLong();
    private final AtomicLong messagesFailed   = new AtomicLong();

    public MessageConsumerService(RabbitMQConsumerConfig config,
                                  ServerHttpClient httpClient) {
        this.config     = config;
        this.httpClient = httpClient;
    }

    // -------------------------
    // Initialization
    // -------------------------

    /**
     * Starts consumer worker threads on application startup.
     * Queues are distributed across workers using round-robin assignment.
     */
    @PostConstruct
    public void init() throws Exception {
        List<List<String>> assignments = assignQueues();

        for (int i = 0; i < consumerThreadCount; i++) {
            List<String> queuesForWorker = assignments.get(i);
            Thread worker = new Thread(() -> runWorker(queuesForWorker));
            worker.setName("consumer-worker-" + i);
            worker.start();
            workers.add(worker);
        }

        logger.info("Started {} consumer workers for {} queues",
                consumerThreadCount, queueCount);
    }

    // -------------------------
    // Queue Distribution
    // -------------------------

    /**
     * Distributes queues across worker threads using round-robin assignment.
     * Example: 20 queues, 10 workers -> each worker gets 2 queues
     *
     * @return list of queue name lists, one per worker thread
     */
    private List<List<String>> assignQueues() {
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < consumerThreadCount; i++) {
            result.add(new ArrayList<>());
        }
        for (int i = 1; i <= queueCount; i++) {
            int workerIndex = (i - 1) % consumerThreadCount;
            result.get(workerIndex).add("room." + i);
        }
        return result;
    }

    // -------------------------
    // Worker Thread Logic
    // -------------------------

    /**
     * Each worker creates its own Channel and registers consumers for its assigned queues.
     * All basicAck calls happen within this thread, ensuring Channel thread safety.
     *
     * @param queues list of queue names this worker is responsible for
     */
    private void runWorker(List<String> queues) {
        try {
            Channel channel = config.getConnection().createChannel();
            channel.basicQos(prefetch);

            for (String queue : queues) {
                channel.basicConsume(queue, false,
                    (tag, delivery) -> {
                        long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                        try {
                            // Extract messageId for deduplication
                            String body = new String(delivery.getBody());
                            String messageId = extractMessageId(body);

                            // Check for duplicate
                            if (messageId != null && !processedIds.add(messageId)) {
                                channel.basicAck(deliveryTag, false);
                                logger.debug("Duplicate message skipped: {}", messageId);
                                return;
                            }

                            // Prevent unbounded memory growth
                            if (processedIds.size() > MAX_PROCESSED_IDS) {
                                processedIds.clear();
                            }

                            // ACK FIRST before broadcast (improves consumer throughput)
                            // Broadcast is async so failures won't affect ack
                            channel.basicAck(deliveryTag, false);
                            messagesConsumed.incrementAndGet();

                            // Async broadcast to all server-v2 instances
                            httpClient.broadcast(delivery.getBody());

                        } catch (Exception e) {
                            messagesFailed.incrementAndGet();
                            try {
                                channel.basicNack(deliveryTag, false, false);
                            } catch (IOException nackEx) {
                                logger.error("Nack failed for queue {}", queue, nackEx);
                            }
                        }
                    },
                    tag -> logger.warn("Consumer cancelled for queue: {}", queue)
                );
                logger.info("Consumer registered for queue: {}", queue);
            }

        } catch (Exception e) {
            logger.error("Worker thread crashed", e);
        }
    }

    // -------------------------
    // Deduplication Helper
    // -------------------------

    /**
     * Extracts messageId from raw JSON bytes without full deserialization.
     * Returns null if messageId cannot be extracted.
     *
     * @param json raw JSON string
     * @return messageId string or null
     */
    private String extractMessageId(String json) {
        try {
            int idx = json.indexOf("\"messageId\"");
            if (idx == -1) return null;
            int start = json.indexOf("\"", idx + 11) + 1;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------
    // Shutdown
    // -------------------------

    /**
     * Interrupts all worker threads on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down MessageConsumerService...");
        for (Thread worker : workers) {
            worker.interrupt();
        }
        logger.info("MessageConsumerService shut down. Consumed: {}, Failed: {}",
                messagesConsumed.get(), messagesFailed.get());
    }

    // -------------------------
    // Metrics
    // -------------------------

    /**
     * Returns the total number of successfully consumed and acknowledged messages.
     *
     * @return consumed message count
     */
    public long getMessagesConsumed() {
        return messagesConsumed.get();
    }

    /**
     * Returns the total number of messages that failed processing.
     *
     * @return failed message count
     */
    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    /**
     * Returns the configured number of consumer worker threads.
     *
     * @return consumer thread count
     */
    public int getActiveConsumerCount() {
        return consumerThreadCount;
    }
}