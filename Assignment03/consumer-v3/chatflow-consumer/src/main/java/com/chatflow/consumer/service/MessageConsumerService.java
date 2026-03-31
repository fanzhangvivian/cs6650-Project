package com.chatflow.consumer.service;

import com.chatflow.consumer.config.RabbitMQConsumerConfig;
import com.chatflow.consumer.model.MessageEntity;
import com.chatflow.consumer.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages consumer worker threads that pull messages from RabbitMQ queues,
 * broadcast them to server-v2 instances, and enqueue them for persistence.
 *
 * Critical fixes:
 * 1. Only effectiveWorkers = min(queueCount, consumerThreadCount) are started.
 *    This avoids empty workers when there are more threads than queues.
 * 2. Owner-thread ACK model:
 *    DeliverCallback submits processing work to broadcastExecutor, but only the
 *    owner thread that created the Channel executes basicAck/basicNack.
 *    Worker threads never touch RabbitMQ channels.
 * 3. Channel generation awareness:
 *    Results produced for an old/closed channel are discarded so background
 *    tasks cannot block forever writing to a dead completion queue.
 */
@DependsOn("rabbitMQConsumerConfig")
@Service
public class MessageConsumerService {

    private static final Logger logger =
            LoggerFactory.getLogger(MessageConsumerService.class);

    private static final String DLQ_QUEUE = "chat.dlq";
    private static final int MAX_PROCESSED_IDS = 10000;

    private enum AckAction {
        ACK,
        NACK_REQUEUE
    }

    private static final class CompletionResult {
        private final long deliveryTag;
        private final AckAction action;
        private final byte[] body;
        private final String messageId;
        private final String reason;

        private CompletionResult(long deliveryTag, AckAction action,
                                 byte[] body, String messageId, String reason) {
            this.deliveryTag = deliveryTag;
            this.action = action;
            this.body = body;
            this.messageId = messageId;
            this.reason = reason;
        }

        private static CompletionResult ack(long deliveryTag, byte[] body, String messageId) {
            return new CompletionResult(deliveryTag, AckAction.ACK, body, messageId, null);
        }

        private static CompletionResult nack(long deliveryTag, byte[] body,
                                             String messageId, String reason) {
            return new CompletionResult(deliveryTag, AckAction.NACK_REQUEUE, body, messageId, reason);
        }
    }

    private static final class WorkerChannelContext {
        private final LinkedBlockingQueue<CompletionResult> completionQueue =
                new LinkedBlockingQueue<>();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private boolean enqueue(CompletionResult result) {
            if (!active.get()) {
                return false;
            }
            return completionQueue.offer(result);
        }

        private void deactivate() {
            active.set(false);
            completionQueue.clear();
        }
    }

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
    private int activeConsumerCount;

    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    // Metrics
    private final AtomicLong messagesConsumed = new AtomicLong();
    private final AtomicLong messagesFailed = new AtomicLong();
    private final AtomicLong messagesBuffered = new AtomicLong();
    private final AtomicLong bufferFullNacks = new AtomicLong();
    private final AtomicLong malformedMessages = new AtomicLong();

    public MessageConsumerService(RabbitMQConsumerConfig config,
                                  ServerHttpClient httpClient,
                                  DatabaseWriterService databaseWriterService) {
        this.config = config;
        this.httpClient = httpClient;
        this.databaseWriterService = databaseWriterService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        activeConsumerCount = Math.min(consumerThreadCount, queueCount);
        broadcastExecutor = Executors.newFixedThreadPool(activeConsumerCount * 2);
        declareDlq();

        List<List<String>> assignments = assignQueues();
        for (int i = 0; i < activeConsumerCount; i++) {
            final List<String> queuesForWorker = assignments.get(i);
            Thread worker = new Thread(() -> runWorker(queuesForWorker));
            worker.setName("consumer-worker-" + i);
            worker.start();
            workers.add(worker);
        }

        logger.info("Started {} consumer workers, {} queues, {} broadcast threads, DLQ={}",
                activeConsumerCount, queueCount, activeConsumerCount * 2, DLQ_QUEUE);
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
        for (int i = 0; i < activeConsumerCount; i++) {
            result.add(new ArrayList<>());
        }
        for (int i = 1; i <= queueCount; i++) {
            result.get((i - 1) % activeConsumerCount).add("room." + i);
        }
        return result;
    }

    private void runWorker(List<String> queues) {
        while (!Thread.currentThread().isInterrupted()) {
            Channel channel = null;
            WorkerChannelContext channelContext = new WorkerChannelContext();
            try {
                channel = config.getConnection().createChannel();
                channel.basicQos(prefetch);
                final Channel finalChannel = channel;
                final WorkerChannelContext finalContext = channelContext;

                for (String queue : queues) {
                    final String queueName = queue;
                    finalChannel.basicConsume(queueName, false,
                            (tag, delivery) -> {
                                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                                byte[] body = delivery.getBody();
                                String bodyStr = new String(body, StandardCharsets.UTF_8);
                                String messageId = extractMessageId(bodyStr);

                                if (messageId != null && !processedIds.add(messageId)) {
                                    if (!finalContext.enqueue(CompletionResult.ack(deliveryTag, body, messageId))) {
                                        logger.warn("Dropped duplicate completion result for {}", messageId);
                                    }
                                    logger.debug("Duplicate skipped (in-memory): {}", messageId);
                                    return;
                                }
                                if (processedIds.size() > MAX_PROCESSED_IDS) {
                                    processedIds.clear();
                                }

                                broadcastExecutor.submit(() -> processMessage(
                                        deliveryTag, body, bodyStr, messageId, queueName, finalContext));
                            },
                            tag -> logger.warn("Consumer cancelled for queue: {}", tag));

                    logger.info("Consumer registered for queue: {}", queueName);
                }

                while (!Thread.currentThread().isInterrupted() && finalChannel.isOpen()) {
                    CompletionResult result = finalContext.completionQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (result != null) {
                        executeAckOrNack(finalChannel, result);
                    }
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
                channelContext.deactivate();
                if (channel != null && channel.isOpen()) {
                    try {
                        channel.close();
                    } catch (Exception e) {
                        logger.warn("Failed to close worker channel", e);
                    }
                }
            }
        }
    }

    private void processMessage(long deliveryTag, byte[] body, String bodyStr,
                                String messageId, String queueName,
                                WorkerChannelContext channelContext) {
        try {
            boolean broadcastSuccess = httpClient.broadcastParallel(body);
            if (!broadcastSuccess) {
                messagesFailed.incrementAndGet();
                enqueueResult(channelContext,
                        CompletionResult.nack(deliveryTag, body, messageId, "broadcast failed"),
                        messageId);
                return;
            }

            MessageEntity entity;
            try {
                QueueMessage queueMessage = objectMapper.readValue(bodyStr, QueueMessage.class);
                entity = MessageEntity.from(queueMessage);
            } catch (Exception e) {
                malformedMessages.incrementAndGet();
                logger.error("Deserialization failed for message {}, acking to prevent loop: {}",
                        messageId, e.getMessage());
                enqueueResult(channelContext,
                        CompletionResult.ack(deliveryTag, body, messageId),
                        messageId);
                return;
            }

            boolean offered = databaseWriterService.offer(entity);
            if (!offered) {
                bufferFullNacks.incrementAndGet();
                messagesFailed.incrementAndGet();
                enqueueResult(channelContext,
                        CompletionResult.nack(deliveryTag, body, messageId, "DB buffer full"),
                        messageId);
                return;
            }

            messagesBuffered.incrementAndGet();
            enqueueResult(channelContext,
                    CompletionResult.ack(deliveryTag, body, messageId),
                    messageId);

            logger.debug("Message {} processed: broadcast ✓, buffer ✓", messageId);
        } catch (Exception e) {
            messagesFailed.incrementAndGet();
            logger.error("Unexpected error processing message {} from {}", messageId, queueName, e);
            enqueueResult(channelContext,
                    CompletionResult.nack(deliveryTag, body, messageId, "unexpected: " + e.getMessage()),
                    messageId);
        }
    }

    private void enqueueResult(WorkerChannelContext channelContext,
                               CompletionResult result,
                               String messageId) {
        if (!channelContext.enqueue(result)) {
            logger.warn("Dropped completion result for {} because channel context is inactive",
                    messageId);
        }
    }

    private void executeAckOrNack(Channel channel, CompletionResult result) {
        try {
            if (result.action == AckAction.ACK) {
                channel.basicAck(result.deliveryTag, false);
                messagesConsumed.incrementAndGet();
            } else {
                channel.basicNack(result.deliveryTag, false, true);
                logger.warn("Message {} nacked for requeue: {}", result.messageId, result.reason);
            }
        } catch (IOException e) {
            logger.error("Ack/Nack failed for message {}, publishing to DLQ",
                    result.messageId, e);
            publishToDlq(result.body);
        }
    }

    private void publishToDlq(byte[] body) {
        try {
            Channel dlqChannel = config.getConnection().createChannel();
            dlqChannel.basicPublish(
                    "",
                    DLQ_QUEUE,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    body
            );
            dlqChannel.close();
            logger.info("Original message body published to DLQ: {}", DLQ_QUEUE);
        } catch (Exception e) {
            logger.error("Failed to publish to DLQ {}", DLQ_QUEUE, e);
        }
    }

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
        logger.info("MessageConsumerService shutdown. Consumed={}, Failed={}, Buffered={}, " +
                        "BufferFullNacks={}, Malformed={}",
                messagesConsumed.get(), messagesFailed.get(),
                messagesBuffered.get(), bufferFullNacks.get(),
                malformedMessages.get());
    }

    public long getMessagesConsumed() {
        return messagesConsumed.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    public long getActiveConsumerCount() {
        return activeConsumerCount;
    }

    public long getMessagesBuffered() {
        return messagesBuffered.get();
    }

    public long getBufferFullNacks() {
        return bufferFullNacks.get();
    }

    public long getMalformedMessages() {
        return malformedMessages.get();
    }
}
