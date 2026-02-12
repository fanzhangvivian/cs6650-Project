package com.chatflow.client.visualization;

import com.chatflow.client.model.MessageRecord;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Throughput visualization for Part 3
 * Generates line chart of messages/second over time in 10-second buckets
 */
public class ThroughputChart {
    
    private final List<MessageRecord> records;
    
    public ThroughputChart(List<MessageRecord> records) {
        this.records = records;
    }
    
    /**
     * Generate and save throughput chart
     */
    public void generateChart(String outputPath) throws IOException {
        System.out.println("\n┌─────────────────────────────────────┐");
        System.out.println("│  Generating Throughput Chart        │");
        System.out.println("└─────────────────────────────────────┘\n");
        
        // Calculate throughput per 10-second bucket
        Map<Integer, Integer> buckets = calculateThroughputBuckets();
        
        if (buckets.isEmpty()) {
            System.err.println("❌ No data available for chart generation");
            return;
        }
        
        // Create dataset
        DefaultCategoryDataset dataset = createDataset(buckets);
        
        // Create chart
        JFreeChart chart = ChartFactory.createLineChart(
            "Throughput Over Time",           // Title
            "Time (seconds)",                  // X-axis label
            "Messages per Second",             // Y-axis label
            dataset,                           // Dataset
            PlotOrientation.VERTICAL,          // Orientation
            true,                              // Legend
            true,                              // Tooltips
            false                              // URLs
        );
        
        // Customize chart appearance
        chart.setBackgroundPaint(java.awt.Color.WHITE);
        
        // Save to file
        File chartFile = new File(outputPath);
        ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600);
        
        System.out.println("✅ Chart saved: " + outputPath);
        System.out.println("   Size: 800x600 pixels");
        System.out.println("   Time buckets: " + buckets.size());
    }
    
    /**
     * Calculate throughput in 10-second buckets
     */
    private Map<Integer, Integer> calculateThroughputBuckets() {
        if (records.isEmpty()) {
            return Collections.emptyMap();
        }
        
        // Find min timestamp to use as baseline
        long minTimestamp = records.stream()
            .mapToLong(MessageRecord::getTimestamp)
            .min()
            .orElse(0);
        
        // Group messages into 10-second buckets
        Map<Integer, Integer> buckets = new TreeMap<>();
        
        for (MessageRecord record : records) {
            long elapsed = record.getTimestamp() - minTimestamp;
            int bucket = (int) (elapsed / 10000); // 10 seconds in ms
            
            buckets.merge(bucket, 1, Integer::sum);
        }
        
        return buckets;
    }
    
    /**
     * Create dataset from buckets
     */
    private DefaultCategoryDataset createDataset(Map<Integer, Integer> buckets) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        
        for (Map.Entry<Integer, Integer> entry : buckets.entrySet()) {
            int bucketNumber = entry.getKey();
            int messageCount = entry.getValue();
            
            // Calculate messages per second (bucket is 10 seconds)
            double messagesPerSecond = messageCount / 10.0;
            
            // Add to dataset
            String timeLabel = (bucketNumber * 10) + "s";
            dataset.addValue(messagesPerSecond, "Throughput", timeLabel);
        }
        
        return dataset;
    }
}
