package com.chatflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight circuit breaker for RabbitMQ publish operations.
 *
 * Transitions between three states:
 *   CLOSED    - normal operation, all publish attempts are allowed
 *   OPEN      - tripped state, all publish attempts are rejected immediately
 *   HALF_OPEN - recovery probe state, one test request is allowed through
 *
 * State transitions:
 *   CLOSED    -> OPEN      : consecutive failures exceed failureThreshold
 *   OPEN      -> HALF_OPEN : resetTimeoutMs has elapsed since last failure
 *   HALF_OPEN -> CLOSED    : the probe request succeeds
 *   HALF_OPEN -> OPEN      : the probe request fails
 */
@Service
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    // -------------------------
    // State Definition
    // -------------------------

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    // -------------------------
    // Configuration
    // -------------------------

    @Value("${circuit-breaker.failure-threshold}")
    private int failureThreshold;

    @Value("${circuit-breaker.reset-timeout-ms}")
    private long resetTimeoutMs;

    // -------------------------
    // Internal State
    // -------------------------

    private final AtomicReference<State> state        = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount            = new AtomicInteger(0);
    private final AtomicLong lastFailureTime            = new AtomicLong(0);
    // Ensures only one probe request is allowed through in HALF_OPEN state
    private final AtomicBoolean probeInProgress         = new AtomicBoolean(false);

    // -------------------------
    // Core API
    // -------------------------

    /**
     * Determines whether a publish attempt should be allowed.
     *
     * - CLOSED:    always allowed
     * - OPEN:      rejected unless resetTimeoutMs has elapsed, in which case
     *              transitions to HALF_OPEN and allows one probe request
     * - HALF_OPEN: allows the single probe request through
     *
     * @return true if the request is allowed, false if it should be rejected
     */
    public boolean isAllowed() {
        State current = state.get();

        switch (current) {
            case CLOSED:
                return true;

            case OPEN:
                long elapsed = System.currentTimeMillis() - lastFailureTime.get();
                if (elapsed >= resetTimeoutMs) {
                    // Enough time has passed - attempt recovery probe
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        logger.info("CircuitBreaker transitioning OPEN -> HALF_OPEN, allowing probe request");
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                // Only allow one probe request through at a time
                // compareAndSet ensures only one thread wins the probe slot
                return probeInProgress.compareAndSet(false, true);

            default:
                return false;
        }
    }

    /**
     * Records a successful publish operation.
     * Resets failure count and transitions back to CLOSED from any state.
     */
    public void recordSuccess() {
        State current = state.get();
        if (current != State.CLOSED) {
            state.set(State.CLOSED);
            logger.info("CircuitBreaker transitioning {} -> CLOSED after successful publish", current);
        }
        failureCount.set(0);
        probeInProgress.set(false);
    }

    /**
     * Records a failed publish operation.
     * Increments the failure counter and trips the circuit breaker to OPEN
     * if the failure threshold is exceeded.
     */
    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        int failures = failureCount.incrementAndGet();

        logger.warn("CircuitBreaker recorded failure #{}", failures);

        if (failures >= failureThreshold) {
            State prev = state.getAndSet(State.OPEN);
            if (prev != State.OPEN) {
                logger.error("CircuitBreaker tripped: {} -> OPEN after {} consecutive failures",
                    prev, failures);
            }
        }
        probeInProgress.set(false);
    }

    // -------------------------
    // Metrics / Monitoring
    // -------------------------

    /**
     * Returns the current circuit breaker state.
     *
     * @return current State (CLOSED, OPEN, or HALF_OPEN)
     */
    public State getState() {
        return state.get();
    }

    /**
     * Returns the current consecutive failure count.
     *
     * @return failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }
}