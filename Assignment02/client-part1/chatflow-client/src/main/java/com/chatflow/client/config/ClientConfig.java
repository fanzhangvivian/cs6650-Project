package com.chatflow.client.config;

/**
 * Configuration for Client Part 1 - Basic Load Testing
 */
public class ClientConfig {
    
    // ============ Server Configuration ============
    public static final String SERVER_URL = "ws://52.90.172.30:8080/chat/";
    
    // ============ Test Configuration ============
    public static final int TOTAL_MESSAGES = 500_000;
    
    // Phase 1: Warmup
    public static final int WARMUP_THREADS = 32;
    public static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    public static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD;
    
    // Phase 2: Main Load
    public static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;
    public static final int MAIN_PHASE_THREADS = 200;
    
    // ============ Message Generation ============
    public static final int MIN_USER_ID = 1;
    public static final int MAX_USER_ID = 100_000;
    public static final int MIN_ROOM_ID = 1;
    public static final int MAX_ROOM_ID = 20;
    
    // Message type distribution
    public static final double TEXT_PROBABILITY = 0.90;
    public static final double JOIN_PROBABILITY = 0.05;
    public static final double LEAVE_PROBABILITY = 0.05;
    
    // ============ Connection Configuration ============
    public static final int MAX_RETRIES = 5;
    public static final int INITIAL_BACKOFF_MS = 50;
    public static final int CONNECTION_TIMEOUT_MS = 5000;
    
    // ============ Queue Configuration ============
    public static final int QUEUE_CAPACITY = 10_000;
    
    // ============ Feature Flags ============
    // Part 1: Basic metrics only
    public static final boolean ENABLE_DETAILED_METRICS = false;
}