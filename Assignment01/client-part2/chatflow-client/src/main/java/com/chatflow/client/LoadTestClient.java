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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Main Load Test Client for Client Part 2
 * CS6650 Assignment 1 - Detailed Performance Analysis
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
            System.out.println("   Throughput: " + String.format("%.2f", warmupThroughput) + " msg/sec");
            System.out.println("   Active connections: " + connectionPool.getActiveConnectionCount() + "\n");
            
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
            System.out.println("   Duration: " + mainDuration + " ms");
            System.out.println("   Throughput: " + String.format("%.2f", mainThroughput) + " msg/sec");
            System.out.println("   Active connections: " + connectionPool.getActiveConnectionCount() + "\n");
            
            // Close all connections
            connectionPool.closeAll();
            
            // ========== Overall Summary ==========
            long overallDuration = System.currentTimeMillis() - overallStartTime;
            
            System.out.println("┌─────────────────────────────────────┐");
            System.out.println("│  Overall Test Results               │");
            System.out.println("└─────────────────────────────────────┘");
            
            // Print basic summary
            metricsCollector.printSummary(overallDuration, connectionPool.getTotalConnectionsCreated());
            
            System.out.println("Phase Breakdown:");
            System.out.println("  Warmup:    " + warmupDuration + " ms (" + 
                             String.format("%.2f", warmupThroughput) + " msg/sec)");
            System.out.println("  Main Load: " + mainDuration + " ms (" + 
                             String.format("%.2f", mainThroughput) + " msg/sec)");
            
            // ========== Detailed Performance Analysis ==========
            System.out.println("\n┌─────────────────────────────────────┐");
            System.out.println("│  Detailed Performance Analysis      │");
            System.out.println("└─────────────────────────────────────┘");
            
            // Statistical analysis
            PerformanceAnalyzer analyzer = new PerformanceAnalyzer(metricsCollector.getMessageRecords());
            analyzer.printStatistics();
            
            // ========== Save CSV ==========
            if (ClientConfig.ENABLE_DETAILED_METRICS) {
                System.out.println("Saving detailed metrics to CSV...");
                CSVWriter csvWriter = new CSVWriter(ClientConfig.CSV_OUTPUT_FILE);
                csvWriter.writeRecords(metricsCollector.getMessageRecords());
            }
            
            // ========== Generate Chart (Part 3) ==========
            if (ClientConfig.ENABLE_VISUALIZATION) {
                try {
                    ThroughputChart chart = new ThroughputChart(metricsCollector.getMessageRecords());
                    chart.generateChart(ClientConfig.CHART_OUTPUT_FILE);
                } catch (Exception e) {
                    System.err.println("❌ Error generating chart: " + e.getMessage());
                }
            }
            
            System.out.println("\n✅ All tests completed successfully!");
            
        } catch (Exception e) {
            System.err.println("❌ Error during load test: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Run a test phase
     */
    private void runPhase(MessageQueue messageQueue, 
                         DetailedMetricsCollector metricsCollector,
                         ConnectionPool connectionPool,
                         int numThreads, 
                         int totalMessages) throws Exception {
        
        System.out.println("Starting " + numThreads + " threads to send " + 
                         totalMessages + " messages...\n");
        
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads + 1);
        
        // Start message generator
        MessageGenerator generator = new MessageGenerator(messageQueue, totalMessages);
        Future<?> generatorFuture = executorService.submit(generator);
        
        // Calculate messages per thread
        int messagesPerThread = totalMessages / numThreads;
        int remainder = totalMessages % numThreads;
        
        // Start sender threads (using DetailedMessageSender)
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
        
        // Wait for generator
        generatorFuture.get();
        
        // Wait for all senders
        for (Future<?> future : senderFutures) {
            future.get();
        }
        
        // Shutdown
        executorService.shutdown();
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }
}