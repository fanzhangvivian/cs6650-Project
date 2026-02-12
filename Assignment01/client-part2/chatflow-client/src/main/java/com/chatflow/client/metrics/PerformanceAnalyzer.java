package com.chatflow.client.metrics;

import com.chatflow.client.model.MessageRecord;
import com.chatflow.client.model.MessageType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performance analyzer for Client Part 2
 * Calculates statistical metrics from message records
 */
public class PerformanceAnalyzer {
    
    private final List<MessageRecord> records;
    
    public PerformanceAnalyzer(List<MessageRecord> records) {
        this.records = records;
    }
    
    /**
     * Print all statistical analysis
     */
    public void printStatistics() {
        if (records.isEmpty()) {
            System.out.println("No data available for analysis");
            return;
        }
        
        System.out.println("\n========== Performance Statistics ==========");
        
        // Latency statistics
        printLatencyStatistics();
        
        // Throughput per room
        printThroughputPerRoom();
        
        // Message type distribution
        printMessageTypeDistribution();
        
        System.out.println("===========================================\n");
    }
    
    /**
     * Print latency statistics - ONLY for measured messages (latency > 0)
     */
    private void printLatencyStatistics() {
        // Filter out non-measured messages (latency = 0)
        List<Long> latencies = records.stream()
            .map(MessageRecord::getLatencyMs)
            .filter(latency -> latency > 0)  // NEW: Only measured latencies
            .sorted()
            .collect(Collectors.toList());
        
        if (latencies.isEmpty()) {
            System.out.println("No latency data available");
            return;
        }
        
        long mean = (long) latencies.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);
        
        long median = getPercentile(latencies, 50);
        long p95 = getPercentile(latencies, 95);
        long p99 = getPercentile(latencies, 99);
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        
        System.out.println("Response Time Statistics:");
        System.out.println("  (Based on " + latencies.size() + " measured samples)");  // NEW
        System.out.println("  Mean response time: " + mean + " ms");
        System.out.println("  Median response time: " + median + " ms");
        System.out.println("  95th percentile: " + p95 + " ms");
        System.out.println("  99th percentile: " + p99 + " ms");
        System.out.println("  Min response time: " + min + " ms");
        System.out.println("  Max response time: " + max + " ms");
    }
    
    /**
     * Print throughput per room
     */
    private void printThroughputPerRoom() {
        System.out.println("\n--- Throughput per Room ---");
        
        Map<String, Long> roomCounts = records.stream()
            .collect(Collectors.groupingBy(
                MessageRecord::getRoomId, 
                Collectors.counting()
            ));
        
        // Sort by room ID
        roomCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey((a, b) -> {
                try {
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            }))
            .forEach(entry -> 
                System.out.println("  Room " + entry.getKey() + ": " + 
                                 entry.getValue() + " messages")
            );
    }
    
    /**
     * Print message type distribution
     */
    private void printMessageTypeDistribution() {
        System.out.println("\n--- Message Type Distribution ---");
        
        Map<MessageType, Long> typeCounts = records.stream()
            .collect(Collectors.groupingBy(
                MessageRecord::getMessageType, 
                Collectors.counting()
            ));
        
        long total = records.size();
        
        typeCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                double percentage = (entry.getValue() * 100.0) / total;
                System.out.println("  " + entry.getKey() + ": " + 
                                 entry.getValue() + " messages (" + 
                                 String.format("%.2f", percentage) + "%)");
            });
    }
    
    /**
     * Calculate percentile from sorted list
     */
    private long getPercentile(List<Long> sortedList, int percentile) {
        if (sortedList.isEmpty()) {
            return 0;
        }
        
        int index = (int) Math.ceil(sortedList.size() * percentile / 100.0) - 1;
        index = Math.max(0, Math.min(index, sortedList.size() - 1));
        
        return sortedList.get(index);
    }
    
    /**
     * Get latency statistics as map (for testing or external use)
     */
    public Map<String, Long> getLatencyStatistics() {
        List<Long> latencies = records.stream()
            .map(MessageRecord::getLatencyMs)
            .sorted()
            .collect(Collectors.toList());
        
        Map<String, Long> stats = new HashMap<>();
        stats.put("mean", (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        stats.put("median", getPercentile(latencies, 50));
        stats.put("p95", getPercentile(latencies, 95));
        stats.put("p99", getPercentile(latencies, 99));
        stats.put("min", latencies.isEmpty() ? 0 : latencies.get(0));
        stats.put("max", latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1));
        
        return stats;
    }
}