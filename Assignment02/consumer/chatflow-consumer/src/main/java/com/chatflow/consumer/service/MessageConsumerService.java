package com.chatflow.consumer.service;

import com.chatflow.consumer.config.RabbitMQConsumerConfig;
import com.rabbitmq.client.Channel;
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
import java.util.concurrent.atomic.AtomicLong;

@DependsOn("rabbitMQConsumerConfig")
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

    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_PROCESSED_IDS = 10000;

    private final AtomicLong messagesConsumed = new AtomicLong();
    private final AtomicLong messagesFailed = new AtomicLong();

    public MessageConsumerService(RabbitMQConsumerConfig config,
                                  ServerHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @PostConstruct
    public void init() {
        List<List<String>> assignments = assignQueues();
        for (int i = 0; i < consumerThreadCount; i++) {
            final List<String> queuesForWorker = assignments.get(i);
            Thread worker = new Thread(() -> runWorker(queuesForWorker));
            worker.setName("consumer-worker-" + i);
            worker.start();
            workers.add(worker);
        }
        logger.info("Started {} consumer workers for {} queues", consumerThreadCount, queueCount);
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

                // KEY FIX: create a final reference to channel for use inside lambdas
                final Channel finalChannel = channel;

                for (String queue : queues) {
                    final String queueName = queue;

                    finalChannel.basicConsume(queueName, false,
                        (tag, delivery) -> {
                            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                            try {
                                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                                String messageId = extractMessageId(body);

                                // Deduplication
                                if (messageId != null && !processedIds.add(messageId)) {
                                    finalChannel.basicAck(deliveryTag, false);
                                    logger.debug("Duplicate message skipped: {}", messageId);
                                    return;
                                }

                                if (processedIds.size() > MAX_PROCESSED_IDS) {
                                    processedIds.clear();
                                }

                                // Broadcast first, ack only on success
                                boolean success = httpClient.broadcastSync(delivery.getBody());

                                if (success) {
                                    finalChannel.basicAck(deliveryTag, false);
                                    messagesConsumed.incrementAndGet();
                                    logger.debug("Broadcast succeeded for message {}", messageId);
                                } else {
                                    messagesFailed.incrementAndGet();
                                    finalChannel.basicNack(deliveryTag, false, true);
                                    logger.warn("Broadcast failed for message {}, requeued", messageId);
                                }

                            } catch (Exception e) {
                                messagesFailed.incrementAndGet();
                                logger.error("Error consuming message from queue {}", queueName, e);
                                try {
                                    finalChannel.basicNack(deliveryTag, false, true);
                                } catch (IOException nackEx) {
                                    logger.error("Nack failed for queue {}", queueName, nackEx);
                                }
                            }
                        },
                        tag -> logger.warn("Consumer cancelled for queue: {}", queueName)
                    );

                    logger.info("Consumer registered for queue: {}", queueName);
                }

                // Keep worker alive while channel is open
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
                    try {
                        channel.close();
                    } catch (Exception e) {
                        logger.warn("Failed to close worker channel cleanly", e);
                    }
                }
            }
        }
    }

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

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down MessageConsumerService...");
        for (Thread worker : workers) {
            worker.interrupt();
        }
        logger.info("MessageConsumerService shut down. Consumed: {}, Failed: {}",
                messagesConsumed.get(), messagesFailed.get());
    }

    public long getMessagesConsumed() { return messagesConsumed.get(); }
    public long getMessagesFailed()   { return messagesFailed.get(); }
    public long getActiveConsumerCount() { return consumerThreadCount; }
}