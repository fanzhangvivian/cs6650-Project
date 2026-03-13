package com.chatflow.consumer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP client that broadcasts QueueMessages to all configured server-v2 instances.
 * Uses fan-out strategy: each message is POSTed to every server instance
 * so all connected WebSocket sessions receive the broadcast.
 * Broadcast is asynchronous to avoid blocking consumer threads.
 */
@Service
@ConfigurationProperties(prefix = "broadcast")
public class ServerHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(ServerHttpClient.class);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // Populated automatically from broadcast.servers in application.yml
    private List<String> servers = new ArrayList<>();

    // -------------------------
    // Metrics
    // -------------------------

    private final AtomicLong broadcastSuccess = new AtomicLong(0);
    private final AtomicLong broadcastFailed  = new AtomicLong(0);

    public ServerHttpClient() {
    }

    // Required by @ConfigurationProperties for list injection
    public void setServers(List<String> servers) {
        this.servers = servers;
    }

    // -------------------------
    // Broadcast
    // -------------------------

    /**
     * Asynchronously broadcasts a QueueMessage to all configured server-v2 instances.
     * Uses CompletableFuture to avoid blocking the consumer thread.
     * Failures are logged but do not block message processing.
     *
     * @param queueMessage the message to broadcast (as raw JSON bytes)
     */
    public void broadcast(byte[] queueMessage) {
        for (String server : servers) {
            String url = server + "/internal/broadcast";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
                    .build();

            // Async send - does not block consumer thread
            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            broadcastSuccess.incrementAndGet();
                            logger.debug("Broadcast success to {}", server);
                        } else {
                            broadcastFailed.incrementAndGet();
                            logger.warn("Broadcast to {} returned status {}", server, response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        broadcastFailed.incrementAndGet();
                        logger.warn("Broadcast failed to {}: {}", server, e.getMessage());
                        return null;
                    });
        }
    }

    // -------------------------
    // Metrics
    // -------------------------

    /**
     * Returns the total number of successful broadcast HTTP calls.
     *
     * @return success count
     */
    public long getBroadcastSuccess() {
        return broadcastSuccess.get();
    }

    /**
     * Returns the total number of failed broadcast HTTP calls.
     *
     * @return failed count
     */
    public long getBroadcastFailed() {
        return broadcastFailed.get();
    }
}