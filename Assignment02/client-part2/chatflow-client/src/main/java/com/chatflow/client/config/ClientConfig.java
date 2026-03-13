package com.chatflow.client.config;

public class ClientConfig {

    // ============ Server Configuration ============
    public static final String SERVER_URL = "ws://chatflow-alb-1683935178.us-east-1.elb.amazonaws.com/chat";

    // ============ Test Configuration ============
    public static final int TOTAL_MESSAGES = 500_000;

    // Warmup (Assignment Required: 32 × 1000 = 32000 normally)

    public static final int CONNECTIONS_PER_ROOM = 4;
    public static final int WARMUP_THREADS = 32;
    public static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    public static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD;

    // Main Phase
    public static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

    public static final int MAIN_PHASE_THREADS = 256;

    // ============ Message Generation ============
    public static final int MIN_USER_ID = 1;
    public static final int MAX_USER_ID = 100_000;

    public static final int MIN_ROOM_ID = 1;
    public static final int MAX_ROOM_ID = 20;

    public static final double TEXT_PROBABILITY = 0.90;
    public static final double JOIN_PROBABILITY = 0.05;
    public static final double LEAVE_PROBABILITY = 0.05;

    // ============ Connection Configuration ============
    public static final int MAX_RETRIES = 5;
    public static final int INITIAL_BACKOFF_MS = 50;
    public static final int CONNECTION_TIMEOUT_MS = 8000;

    // ============ Queue ============
    public static final int QUEUE_CAPACITY = 100_000;

    // ============ Metrics ============
    public static final boolean ENABLE_DETAILED_METRICS = true;
    public static final String CSV_OUTPUT_FILE = "results/performance-metrics.csv";

    public static final boolean ENABLE_VISUALIZATION = true;
    public static final String CHART_OUTPUT_FILE = "results/throughput-chart.png";
}