package com.chatflow.consumer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for PostgreSQL write operations in consumer-v3.
 *
 * Independent from RabbitMQ's circuit breaker in server-v2.
 * Protects DatabaseWriterService from cascading failures when DB is unavailable.
 *
 * State transitions:
 *   CLOSED    → OPEN      : consecutive failures exceed failureThreshold
 *   OPEN      → HALF_OPEN : resetTimeoutMs elapsed since last failure
 *   HALF_OPEN → CLOSED    : probe write succeeds
 *   HALF_OPEN → OPEN      : probe write fails
 */
@Service
public class DbCircuitBreaker {

    private static final Logger logger =
            LoggerFactory.getLogger(DbCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    @Value("${database.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${database.circuit-breaker.reset-timeout-ms:30000}")
    private long resetTimeoutMs;

    private final AtomicReference<State> state =
            new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount    = new AtomicInteger(0);
    private final AtomicLong    lastFailureTime = new AtomicLong(0);
    private final AtomicBoolean probeInProgress = new AtomicBoolean(false);

    /**
     * Returns true if a DB write attempt should be allowed.
     *
     * CLOSED    → always allowed
     * OPEN      → rejected unless resetTimeoutMs elapsed
     *             if elapsed: transition to HALF_OPEN and allow exactly
     *             one probe via probeInProgress CAS
     * HALF_OPEN → only one probe allowed at a time via probeInProgress CAS
     *
     * Fix vs previous version:
     *   OPEN → HALF_OPEN transition now returns probeInProgress.compareAndSet()
     *   instead of unconditional true. This prevents multiple threads from
     *   simultaneously getting probe permission during the recovery window.
     */
    public boolean isAllowed() {
        State current = state.get();

        switch (current) {
            case CLOSED:
                return true;

            case OPEN:
                long elapsed = System.currentTimeMillis() - lastFailureTime.get();
                if (elapsed >= resetTimeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        logger.info("DbCircuitBreaker: OPEN → HALF_OPEN, allowing probe write");
                        return probeInProgress.compareAndSet(false, true);
                    }
                }
                return false;

            case HALF_OPEN:
                return probeInProgress.compareAndSet(false, true);

            default:
                return false;
        }
    }

    /**
     * Records a successful DB write.
     * Resets failure count and transitions back to CLOSED.
     */
    public void recordSuccess() {
        State current = state.get();
        if (current != State.CLOSED) {
            state.set(State.CLOSED);
            logger.info("DbCircuitBreaker: {} → CLOSED after successful write", current);
        }
        failureCount.set(0);
        probeInProgress.set(false);
    }

    /**
     * Records a failed DB write.
     * Trips to OPEN if failure threshold exceeded.
     */
    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        int failures = failureCount.incrementAndGet();
        logger.warn("DbCircuitBreaker: failure #{}", failures);

        if (failures >= failureThreshold) {
            State prev = state.getAndSet(State.OPEN);
            if (prev != State.OPEN) {
                logger.error("DbCircuitBreaker: {} → OPEN after {} consecutive failures",
                        prev, failures);
            }
        }
        probeInProgress.set(false);
    }

    public State getState()        { return state.get(); }
    public int   getFailureCount() { return failureCount.get(); }
}