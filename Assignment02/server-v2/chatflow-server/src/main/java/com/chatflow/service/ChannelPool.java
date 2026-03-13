package com.chatflow.service;

import com.chatflow.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe pool of RabbitMQ channels.
 * Maintains a fixed number of reusable channels to avoid the overhead
 * of creating and destroying channels per message publish.
 */
@Service
public class ChannelPool {

    private static final Logger logger = LoggerFactory.getLogger(ChannelPool.class);

    private final RabbitMQConfig rabbitMQConfig;

    @Value("${rabbitmq.channel-pool-size}")
    private int configuredPoolSize;

    private BlockingQueue<Channel> pool;
    private int actualPoolSize;

    public ChannelPool(RabbitMQConfig rabbitMQConfig) {
        this.rabbitMQConfig = rabbitMQConfig;
    }

    // -------------------------
    // Initialization
    // -------------------------

    /**
     * Initializes the channel pool after Spring injection is complete.
     * Pool size is the greater of the configured value and 2x available CPU cores,
     * ensuring a theoretically justified minimum for I/O-bound workloads.
     */
    @PostConstruct
    public void init() {
        // Use the greater of configured size or 2x CPU cores
        actualPoolSize = Math.max(
            configuredPoolSize,
            Runtime.getRuntime().availableProcessors() * 2
        );

        pool = new ArrayBlockingQueue<>(actualPoolSize);

        int created = 0;
        for (int i = 0; i < actualPoolSize; i++) {
            try {
                Channel channel = rabbitMQConfig.getConnection().createChannel();
                pool.offer(channel);
                created++;
            } catch (IOException e) {
                logger.error("Failed to create channel {} during pool initialization", i, e);
            }
        }

        logger.info("ChannelPool initialized: {}/{} channels created (pool size = max({}, cores*2={}))",
            created, actualPoolSize, configuredPoolSize,
            Runtime.getRuntime().availableProcessors() * 2);
    }

    // -------------------------
    // Borrow and Return
    // -------------------------

    /**
     * Borrows a channel from the pool, waiting up to 3 seconds if none is available.
     * If the borrowed channel is unhealthy, it is discarded and a new one is created.
     *
     * @return a healthy Channel, or null if unavailable within the timeout
     */
    public Channel borrowChannel() {
        try {
            Channel channel = pool.poll(3, TimeUnit.SECONDS);
            if (channel == null) {
                logger.warn("No channel available in pool after 3 seconds");
                return null;
            }

            // Check if channel is still healthy
            if (!channel.isOpen()) {
                logger.warn("Borrowed channel is closed, creating a replacement");
                return createNewChannel();
            }

            return channel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for a channel", e);
            return null;
        }
    }

    /**
     * Returns a channel to the pool after use.
     * If the channel is unhealthy or the pool is full, the channel is closed and discarded.
     *
     * @param channel the channel to return
     */
    public void returnChannel(Channel channel) {
        if (channel == null) {
            return;
        }

        if (!channel.isOpen()) {
            // Channel is broken - discard it and replenish the pool with a new one
            logger.warn("Returned channel is closed, replacing with a new channel");
            replenishChannel();
            return;
        }

        // Return healthy channel to pool
        boolean returned = pool.offer(channel);
        if (!returned) {
            // Pool is full (should not happen under normal conditions)
            logger.warn("Pool is full, closing excess channel");
            closeChannel(channel);
        }
    }

    // -------------------------
    // Internal Helpers
    // -------------------------

    /**
     * Creates a new channel from the shared connection.
     *
     * @return a new open Channel, or null if creation fails
     */
    private Channel createNewChannel() {
        try {
            return rabbitMQConfig.getConnection().createChannel();
        } catch (IOException e) {
            logger.error("Failed to create a new channel", e);
            return null;
        }
    }

    /**
     * Creates a new channel and adds it back to the pool to replace a discarded one.
     */
    private void replenishChannel() {
        Channel newChannel = createNewChannel();
        if (newChannel != null) {
            pool.offer(newChannel);
            logger.info("Replenished pool with a new channel");
        }
    }

    /**
     * Closes a channel quietly, logging any errors.
     *
     * @param channel the channel to close
     */
    private void closeChannel(Channel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException | TimeoutException e) {
            logger.error("Error closing channel", e);
        }
    }

    // -------------------------
    // Shutdown
    // -------------------------

    /**
     * Closes all channels in the pool on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ChannelPool, closing {} channels", pool.size());
        for (Channel channel : pool) {
            closeChannel(channel);
        }
        pool.clear();
    }

    // -------------------------
    // Metrics
    // -------------------------

    /**
     * Returns the number of channels currently available in the pool.
     *
     * @return available channel count
     */
    public int getAvailableChannels() {
        return pool.size();
    }

    /**
     * Returns the actual pool size used at initialization.
     *
     * @return total pool size
     */
    public int getActualPoolSize() {
        return actualPoolSize;
    }
}