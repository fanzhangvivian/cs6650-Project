package com.chatflow.client.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BasicMetricsCollector {
    
    private final AtomicInteger successCount;
    private final AtomicInteger failureCount;
    private final AtomicLong totalConnections;
    private final AtomicInteger reconnections;
    
    public BasicMetricsCollector() {
        this.successCount = new AtomicInteger(0);
        this.failureCount = new AtomicInteger(0);
        this.totalConnections = new AtomicLong(0);
        this.reconnections = new AtomicInteger(0);
    }
    
    public void recordSuccess() {
        successCount.incrementAndGet();
    }
    
    public void recordFailure() {
        failureCount.incrementAndGet();
    }
    
    public void incrementTotalConnections() {
        totalConnections.incrementAndGet();
    }
    
    public void incrementReconnections() {
        reconnections.incrementAndGet();
    }
    
    public int getSuccessCount() {
        return successCount.get();
    }
    
    public int getFailureCount() {
        return failureCount.get();
    }
    
    public long getTotalConnections() {
        return totalConnections.get();
    }
    
    public int getReconnections() {
        return reconnections.get();
    }
    
    /**
     * Print summary with option to override connection count
     */
    public void printSummary(long durationMs, int actualConnections) {
        System.out.println("\n========== Test Summary ==========");
        System.out.println("Successful messages: " + successCount.get());
        System.out.println("Failed messages: " + failureCount.get());
        System.out.println("Total runtime: " + durationMs + " ms");
        
        if (durationMs > 0) {
            double throughput = (successCount.get() * 1000.0) / durationMs;
            System.out.println("Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        }
        
        System.out.println("Total connections: " + actualConnections); // NEW: Use actual count
        System.out.println("Reconnections: " + reconnections.get());
        System.out.println("==================================\n");
    }
    
    // Keep old method for compatibility
    public void printSummary(long durationMs) {
        printSummary(durationMs, (int) totalConnections.get());
    }
}