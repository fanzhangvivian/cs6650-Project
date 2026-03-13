package com.chatflow.consumer.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Establishes and manages the RabbitMQ connection for the Consumer application.
 * Unlike server-v2, this config only creates the Connection.
 * Exchange and queue declaration is handled by server-v2 on startup.
 */
@Configuration
public class RabbitMQConsumerConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConsumerConfig.class);

    @Value("${rabbitmq.host}")
    private String host;

    @Value("${rabbitmq.port}")
    private int port;

    @Value("${rabbitmq.username}")
    private String username;

    @Value("${rabbitmq.password}")
    private String password;

    private Connection connection;

    /**
     * Establishes RabbitMQ connection on startup with auto-recovery enabled.
     * Does not declare exchanges or queues - server-v2 handles infrastructure setup.
     */
    @PostConstruct
    public void init() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        // Enable automatic connection recovery on network failure
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        connection = factory.newConnection("chatflow-consumer");
        logger.info("RabbitMQ connection established to {}:{}", host, port);
    }

    /**
     * Closes the RabbitMQ connection gracefully on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
                logger.info("RabbitMQ connection closed");
            }
        } catch (IOException e) {
            logger.error("Error closing RabbitMQ connection", e);
        }
    }

    /**
     * Returns the shared RabbitMQ Connection for use by MessageConsumerService.
     *
     * @return the active Connection instance
     */
    public Connection getConnection() {
        return connection;
    }
}