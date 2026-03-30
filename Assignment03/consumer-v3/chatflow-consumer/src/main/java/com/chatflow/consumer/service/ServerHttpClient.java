// package com.chatflow.consumer.service;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.boot.context.properties.ConfigurationProperties;
// import org.springframework.stereotype.Service;

// import java.net.URI;
// import java.net.http.HttpClient;
// import java.net.http.HttpRequest;
// import java.net.http.HttpResponse;
// import java.time.Duration;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.concurrent.atomic.AtomicLong;

// /**
//  * HTTP client that broadcasts QueueMessages to all configured server-v2 instances.
//  * Uses fan-out strategy: each message is POSTed to every server instance
//  * so all connected WebSocket sessions receive the broadcast.
//  *
//  * This class now provides:
//  * 1. async broadcast() for optional fire-and-forget usage
//  * 2. synchronous broadcastSync() for consumer ack/nack decisions
//  */
// @Service
// @ConfigurationProperties(prefix = "broadcast")
// public class ServerHttpClient {

//     private static final Logger logger = LoggerFactory.getLogger(ServerHttpClient.class);

//     private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
//             .connectTimeout(Duration.ofSeconds(3))
//             .build();

//     // Populated automatically from broadcast.servers in application.yml
//     private List<String> servers = new ArrayList<>();

//     // -------------------------
//     // Metrics
//     // -------------------------

//     private final AtomicLong broadcastSuccess = new AtomicLong(0);
//     private final AtomicLong broadcastFailed  = new AtomicLong(0);

//     public ServerHttpClient() {
//     }

//     // Required by @ConfigurationProperties for list injection
//     public void setServers(List<String> servers) {
//         this.servers = servers;
//     }

//     // -------------------------
//     // Async Broadcast (optional)
//     // -------------------------

//     /**
//      * Asynchronously broadcasts a QueueMessage to all configured server-v2 instances.
//      * Failures are logged but do not affect RabbitMQ ack/nack decisions.
//      *
//      * @param queueMessage the message to broadcast (as raw JSON bytes)
//      */
//     public void broadcast(byte[] queueMessage) {
//         for (String server : servers) {
//             String url = server + "/internal/broadcast";

//             HttpRequest request = HttpRequest.newBuilder()
//                     .uri(URI.create(url))
//                     .header("Content-Type", "application/json")
//                     .timeout(Duration.ofSeconds(3))
//                     .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
//                     .build();

//             HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
//                     .thenAccept(response -> {
//                         if (response.statusCode() == 200) {
//                             broadcastSuccess.incrementAndGet();
//                             logger.debug("Broadcast success to {}", server);
//                         } else {
//                             broadcastFailed.incrementAndGet();
//                             logger.warn("Broadcast to {} returned status {}", server, response.statusCode());
//                         }
//                     })
//                     .exceptionally(e -> {
//                         broadcastFailed.incrementAndGet();
//                         logger.warn("Broadcast failed to {}: {}", server, e.getMessage());
//                         return null;
//                     });
//         }
//     }

//     // -------------------------
//     // Sync Broadcast for consumer ack/nack
//     // -------------------------

//     /**
//      * Synchronously broadcasts a QueueMessage to all configured server-v2 instances.
//      * Returns true only if ALL servers eventually respond with HTTP 200.
//      *
//      * Retry policy:
//      * - Up to 3 attempts per server
//      * - Small linear backoff between attempts
//      *
//      * @param queueMessage raw JSON bytes from RabbitMQ
//      * @return true if all servers succeeded, false otherwise
//      */
//     public boolean broadcastSync(byte[] queueMessage) {
//         if (servers == null || servers.isEmpty()) {
//             logger.warn("No servers configured, broadcast skipped");
//             return false;
//         }

//         boolean allSuccess = true;

//         for (String server : servers) {
//             boolean serverSuccess = false;
//             String url = server + "/internal/broadcast";

//             for (int attempt = 0; attempt < 3; attempt++) {
//                 HttpRequest request = HttpRequest.newBuilder()
//                         .uri(URI.create(url))
//                         .header("Content-Type", "application/json")
//                         .timeout(Duration.ofSeconds(2))
//                         .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
//                         .build();

//                 try {
//                     HttpResponse<String> response =
//                             HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

//                     if (response.statusCode() == 200) {
//                         broadcastSuccess.incrementAndGet();
//                         serverSuccess = true;
//                         logger.debug("Broadcast success to {} on attempt {}", server, attempt + 1);
//                         break;
//                     } else {
//                         broadcastFailed.incrementAndGet();
//                         logger.warn("Broadcast to {} returned {} on attempt {}",
//                                 server, response.statusCode(), attempt + 1);
//                     }
//                 } catch (Exception e) {
//                     broadcastFailed.incrementAndGet();
//                     logger.warn("Broadcast failed to {} on attempt {}: {}",
//                             server, attempt + 1, e.getMessage());
//                 }

//                 // Small backoff before retry
//                 try {
//                     Thread.sleep(100L * (attempt + 1));
//                 } catch (InterruptedException ie) {
//                     Thread.currentThread().interrupt();
//                     logger.warn("Broadcast retry interrupted for {}", server);
//                     return false;
//                 }
//             }

//             if (!serverSuccess) {
//                 allSuccess = false;
//             }
//         }

//         return allSuccess;
//     }

//     // -------------------------
//     // Metrics
//     // -------------------------

//     /**
//      * Returns the total number of successful broadcast HTTP calls.
//      *
//      * @return success count
//      */
//     public long getBroadcastSuccess() {
//         return broadcastSuccess.get();
//     }

//     /**
//      * Returns the total number of failed broadcast HTTP calls.
//      *
//      * @return failed count
//      */
//     public long getBroadcastFailed() {
//         return broadcastFailed.get();
//     }
// }



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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP client that broadcasts QueueMessages to all configured server-v2 instances.
 *
 * Key design:
 * - broadcastParallel(): fires all HTTP requests simultaneously using CompletableFuture.
 *   Total wait time = slowest server response, NOT sum of all server responses.
 *   This is critical for multi-instance deployments (2/4 instances).
 * - broadcastSync(): kept for reference, but no longer used in hot path.
 * - broadcast(): fire-and-forget async, kept for compatibility.
 */
@Service
@ConfigurationProperties(prefix = "broadcast")
public class ServerHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(ServerHttpClient.class);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private List<String> servers = new ArrayList<>();

    private final AtomicLong broadcastSuccess = new AtomicLong(0);
    private final AtomicLong broadcastFailed  = new AtomicLong(0);

    public ServerHttpClient() {}

    public void setServers(List<String> servers) {
        this.servers = servers;
    }

    // -------------------------
    // Parallel Broadcast (primary method)
    // -------------------------

    /**
     * Broadcasts to ALL servers IN PARALLEL using CompletableFuture.allOf().
     *
     * All HTTP requests fire simultaneously.
     * Total latency = max(server_1_latency, server_2_latency, ..., server_N_latency)
     * NOT server_1 + server_2 + ... + server_N (serial).
     *
     * Returns true only if ALL servers responded with HTTP 200.
     *
     * @param queueMessage raw JSON bytes from RabbitMQ
     * @return true if all servers succeeded
     */
    public boolean broadcastParallel(byte[] queueMessage) {
        if (servers == null || servers.isEmpty()) {
            logger.warn("No servers configured, broadcast skipped");
            return false;
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (String server : servers) {
            String url = server + "/internal/broadcast";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
                    .build();

            CompletableFuture<Boolean> future = HTTP_CLIENT
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            broadcastSuccess.incrementAndGet();
                            logger.debug("Broadcast success to {}", server);
                            return true;
                        } else {
                            broadcastFailed.incrementAndGet();
                            logger.warn("Broadcast to {} returned {}", server, response.statusCode());
                            return false;
                        }
                    })
                    .exceptionally(e -> {
                        broadcastFailed.incrementAndGet();
                        logger.warn("Broadcast failed to {}: {}", server, e.getMessage());
                        return false;
                    });

            futures.add(future);
        }

        // Wait for ALL servers to respond in parallel
        // Total wait = slowest server, not sum of all servers
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(3, TimeUnit.SECONDS);

            return futures.stream()
                    .map(f -> f.getNow(false))
                    .allMatch(Boolean.TRUE::equals);

        } catch (Exception e) {
            logger.warn("broadcastParallel timed out or interrupted: {}", e.getMessage());
            broadcastFailed.incrementAndGet();
            return false;
        }
    }

    // -------------------------
    // Async fire-and-forget (kept for compatibility)
    // -------------------------

    /**
     * Fire-and-forget async broadcast.
     * Does not block caller, does not affect ack/nack decisions.
     */
    public void broadcast(byte[] queueMessage) {
        for (String server : servers) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(server + "/internal/broadcast"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) broadcastSuccess.incrementAndGet();
                        else broadcastFailed.incrementAndGet();
                    })
                    .exceptionally(e -> { broadcastFailed.incrementAndGet(); return null; });
        }
    }

    // -------------------------
    // Sync broadcast (kept for reference, no longer used in hot path)
    // -------------------------

    public boolean broadcastSync(byte[] queueMessage) {
        if (servers == null || servers.isEmpty()) return false;
        boolean allSuccess = true;
        for (String server : servers) {
            String url = server + "/internal/broadcast";
            boolean serverSuccess = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(queueMessage))
                        .build();
                try {
                    HttpResponse<String> response =
                            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        broadcastSuccess.incrementAndGet();
                        serverSuccess = true;
                        break;
                    } else {
                        broadcastFailed.incrementAndGet();
                    }
                } catch (Exception e) {
                    broadcastFailed.incrementAndGet();
                }
                try { Thread.sleep(100L * (attempt + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
            if (!serverSuccess) allSuccess = false;
        }
        return allSuccess;
    }

    public long getBroadcastSuccess() { return broadcastSuccess.get(); }
    public long getBroadcastFailed()  { return broadcastFailed.get(); }
}