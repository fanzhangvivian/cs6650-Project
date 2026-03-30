package com.chatflow.client;

import com.chatflow.client.config.ClientConfig;
import com.chatflow.client.generator.MessageGenerator;
import com.chatflow.client.metrics.DetailedMetricsCollector;
import com.chatflow.client.metrics.PerformanceAnalyzer;
import com.chatflow.client.metrics.CSVWriter;
import com.chatflow.client.queue.MessageQueue;
import com.chatflow.client.sender.ConnectionPool;
import com.chatflow.client.sender.DetailedMessageSender;
import com.chatflow.client.visualization.ThroughputChart;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Main Load Test Client for Client Part 2
 * CS6650 Assignment 1 - Detailed Performance Analysis
 * Assignment 3 addition: calls Metrics API after test completes
 */
public class LoadTestClient {

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║  ChatFlow Load Test Client - Part 2                  ║");
        System.out.println("║  CS6650 Assignment 1 - Detailed Metrics              ║");
        System.out.println("╚════════════════════════════════════════════════════════╝\n");

        System.out.println("Server URL: " + ClientConfig.SERVER_URL);
        System.out.println("Total messages: " + ClientConfig.TOTAL_MESSAGES);
        System.out.println("Warmup: " + ClientConfig.WARMUP_THREADS + " threads × " +
                ClientConfig.WARMUP_MESSAGES_PER_THREAD + " messages = " +
                ClientConfig.WARMUP_TOTAL + " messages");
        System.out.println("Main phase: " + ClientConfig.MAIN_PHASE_THREADS + " threads × " +
                (ClientConfig.MAIN_PHASE_MESSAGES / ClientConfig.MAIN_PHASE_THREADS) +
                " messages = " + ClientConfig.MAIN_PHASE_MESSAGES + " messages");
        System.out.println("Detailed metrics: ENABLED");
        System.out.println("CSV output: " + ClientConfig.CSV_OUTPUT_FILE + "\n");

        LoadTestClient client = new LoadTestClient();
        client.runLoadTest();
    }

    public void runLoadTest() {
        MessageQueue messageQueue = new MessageQueue(ClientConfig.QUEUE_CAPACITY);
        DetailedMetricsCollector metricsCollector = new DetailedMetricsCollector();
        ConnectionPool connectionPool = new ConnectionPool();

        long overallStartTime = System.currentTimeMillis();

        try {
            // ========== Phase 1: Warmup ==========
            System.out.println("┌─────────────────────────────────────┐");
            System.out.println("│  Phase 1: Warmup                    │");
            System.out.println("└─────────────────────────────────────┘");

            long warmupStart = System.currentTimeMillis();
            runPhase(messageQueue, metricsCollector, connectionPool,
                    ClientConfig.WARMUP_THREADS,
                    ClientConfig.WARMUP_TOTAL);
            long warmupEnd = System.currentTimeMillis();

            long warmupDuration = warmupEnd - warmupStart;
            double warmupThroughput = (ClientConfig.WARMUP_TOTAL * 1000.0) / warmupDuration;

            System.out.println("\n✅ Warmup Phase Completed:");
            System.out.println("   Duration: " + warmupDuration + " ms");
            System.out.println("   Total messages sent: " + ClientConfig.WARMUP_TOTAL);
            System.out.println("   Successful messages: " + metricsCollector.getSuccessCount());
            System.out.println("   Failed messages: " + metricsCollector.getFailureCount());
            System.out.println("   Throughput: " + String.format("%.2f", warmupThroughput) + " msg/sec");
            System.out.println("   Active connections: " + connectionPool.getActiveConnectionCount() + "\n");
            System.out.println("Total connections created so far: " +
                    connectionPool.getTotalConnectionsCreated() + "\n");

            Thread.sleep(1000);

            // ========== Phase 2: Main Load ==========
            System.out.println("┌─────────────────────────────────────┐");
            System.out.println("│  Phase 2: Main Load                 │");
            System.out.println("└─────────────────────────────────────┘");

            long mainStart = System.currentTimeMillis();
            runPhase(messageQueue, metricsCollector, connectionPool,
                    ClientConfig.MAIN_PHASE_THREADS,
                    ClientConfig.MAIN_PHASE_MESSAGES);
            long mainEnd = System.currentTimeMillis();

            long mainDuration = mainEnd - mainStart;
            double mainThroughput = (ClientConfig.MAIN_PHASE_MESSAGES * 1000.0) / mainDuration;

            System.out.println("\n✅ Main Phase Completed:");
            System.out.println("   Total messages sent: " + ClientConfig.MAIN_PHASE_MESSAGES);
            System.out.println("   Duration: " + mainDuration + " ms");
            System.out.println("   Throughput: " + String.format("%.2f", mainThroughput) + " msg/sec");
            System.out.println("   Active connections: " +
                    connectionPool.getActiveConnectionCount() + "\n");
            System.out.println("Total connections created: " +
                    connectionPool.getTotalConnectionsCreated() + "\n");

            connectionPool.closeAll();

            // ========== Overall Summary ==========
            long overallDuration = System.currentTimeMillis() - overallStartTime;

            System.out.println("┌─────────────────────────────────────┐");
            System.out.println("│  Overall Test Results               │");
            System.out.println("└─────────────────────────────────────┘");

            metricsCollector.printSummary(overallDuration,
                    connectionPool.getTotalConnectionsCreated());

            System.out.println("Phase Breakdown:");
            System.out.println("  Warmup:    " + warmupDuration + " ms (" +
                    String.format("%.2f", warmupThroughput) + " msg/sec)");
            System.out.println("  Main Load: " + mainDuration + " ms (" +
                    String.format("%.2f", mainThroughput) + " msg/sec)");

            // ========== Detailed Performance Analysis ==========
            System.out.println("\n┌─────────────────────────────────────┐");
            System.out.println("│  Detailed Performance Analysis      │");
            System.out.println("└─────────────────────────────────────┘");

            PerformanceAnalyzer analyzer =
                    new PerformanceAnalyzer(metricsCollector.getMessageRecords());
            analyzer.printStatistics();

            // ========== Save CSV ==========
            if (ClientConfig.ENABLE_DETAILED_METRICS) {
                System.out.println("Saving detailed metrics to CSV...");
                CSVWriter csvWriter = new CSVWriter(ClientConfig.CSV_OUTPUT_FILE);
                csvWriter.writeRecords(metricsCollector.getMessageRecords());
            }

            // ========== Generate Chart ==========
            if (ClientConfig.ENABLE_VISUALIZATION) {
                try {
                    ThroughputChart chart =
                            new ThroughputChart(metricsCollector.getMessageRecords());
                    chart.generateChart(ClientConfig.CHART_OUTPUT_FILE);
                } catch (Exception e) {
                    System.err.println("❌ Error generating chart: " + e.getMessage());
                }
            }

            System.out.println("\n✅ All tests completed successfully!");

            // ========== Assignment 3: Metrics API Call ==========
            System.out.println("\nWaiting 10s for consumer to flush final batch to DB...");
            Thread.sleep(10000);

            System.out.println("\n┌─────────────────────────────────────┐");
            System.out.println("│  Assignment 3: Metrics API Results  │");
            System.out.println("└─────────────────────────────────────┘");

            callMetricsApi();

        } catch (Exception e) {
            System.err.println("❌ Error during load test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Calls the Metrics API on server-v2 and prints the JSON result.
     *
     * SERVER_URL:  ws://chatflow-alb-xxx.us-east-1.elb.amazonaws.com/chat
     * Metrics URL: http://chatflow-alb-xxx.us-east-1.elb.amazonaws.com/metrics?roomId=1
     *
     * Fix: uses URI parsing instead of string indexOf("/chat") to correctly
     * extract scheme + host + port, regardless of path format.
     */
    private void callMetricsApi() {
        try {
            String serverUrl = ClientConfig.SERVER_URL;

            // Step 1: ws:// → http://
            String httpUrl = serverUrl
                    .replace("ws://", "http://")
                    .replace("wss://", "https://");

            // Step 2: Use URI to extract scheme + host + port only
            // This correctly handles URLs with or without port numbers
            // e.g. ws://host/chat → http://host (no port)
            // e.g. ws://host:8080/chat → http://host:8080
            URI uri = URI.create(httpUrl);
            String metricsUrl = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() != -1 ? ":" + uri.getPort() : "")
                    + "/metrics?roomId=1";

            System.out.println("Calling Metrics API: " + metricsUrl);

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT);
                Object json = mapper.readValue(response.body(), Object.class);
                String prettyJson = mapper.writeValueAsString(json);

                System.out.println("\n===== METRICS API RESULT =====");
                System.out.println(prettyJson);
                System.out.println("==============================");
                System.out.println("✅ Metrics API call successful (HTTP " +
                        response.statusCode() + ")");
            } else {
                System.err.println("❌ Metrics API returned HTTP " + response.statusCode());
                System.err.println("Response body: " + response.body());
                System.err.println("Manual check: curl \"" + metricsUrl + "\"");
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to call Metrics API: " + e.getMessage());
            System.err.println("Manual check: curl \"http://" +
                    "chatflow-alb-1683935178.us-east-1.elb.amazonaws.com/metrics?roomId=1\"");
        }
    }

    private void runPhase(MessageQueue messageQueue,
                          DetailedMetricsCollector metricsCollector,
                          ConnectionPool connectionPool,
                          int numThreads,
                          int totalMessages) throws Exception {

        System.out.println("Starting " + numThreads + " threads to send " +
                totalMessages + " messages...\n");

        ExecutorService executorService = Executors.newFixedThreadPool(numThreads + 1);

        MessageGenerator generator = new MessageGenerator(messageQueue, totalMessages);
        Future<?> generatorFuture = executorService.submit(generator);

        int messagesPerThread = totalMessages / numThreads;
        int remainder = totalMessages % numThreads;

        List<Future<?>> senderFutures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            int messagesToSend = messagesPerThread;
            if (i < remainder) {
                messagesToSend++;
            }

            DetailedMessageSender sender = new DetailedMessageSender(
                    messageQueue,
                    metricsCollector,
                    connectionPool,
                    messagesToSend
            );
            senderFutures.add(executorService.submit(sender));
        }

        generatorFuture.get();

        for (Future<?> future : senderFutures) {
            future.get();
        }

        executorService.shutdown();
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }
}