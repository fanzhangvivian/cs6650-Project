package com.chatflow.config;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Configures and initializes the RabbitMQ connection, exchange, and queues.
 * Creates a single shared Connection on startup and declares all required
 * infrastructure (exchange + 20 durable queues with bindings).
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);

    @Value("${rabbitmq.host}")
    private String host;

    @Value("${rabbitmq.port}")
    private int port;

    @Value("${rabbitmq.username}")
    private String username;

    @Value("${rabbitmq.password}")
    private String password;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue-count}")
    private int queueCount;

    private Connection connection;

    // -------------------------
    // Startup Initialization
    // -------------------------

    /**
     * Initializes RabbitMQ infrastructure on application startup.
     * - Creates a single TCP connection with auto-recovery enabled
     * - Declares the topic exchange as durable
     * - Declares all room queues as durable with message TTL
     * - Binds each queue to the exchange with routing key room.{id}
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
        factory.setNetworkRecoveryInterval(5000); // retry every 5 seconds

        connection = factory.newConnection("chatflow-server");
        logger.info("RabbitMQ connection established to {}:{}", host, port);

        declareInfrastructure();
    }

    /**
     * Declares the exchange, queues, and bindings using a temporary channel.
     * This channel is closed immediately after setup — it is not part of the pool.
     */
    private void declareInfrastructure() throws IOException, TimeoutException {
        try (Channel channel = connection.createChannel()) {

            // Declare topic exchange as durable
            // durable=true: exchange survives RabbitMQ restart
            channel.exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true);
            logger.info("Declared exchange: {}", exchange);

            // Queue arguments: message TTL and max length
            Map<String, Object> queueArgs = new HashMap<>();
            queueArgs.put("x-message-ttl", 3600000);  // messages expire after 1 hour (adjust before load testing)
            queueArgs.put("x-max-length", 100000);    // max 100k messages per queue

            // Declare one durable queue per room and bind to exchange
            for (int i = 1; i <= queueCount; i++) {
                String queueName  = "room." + i;
                String routingKey = "room." + i;

                // durable=true: queue survives RabbitMQ restart
                // exclusive=false: queue is shared across connections
                // autoDelete=false: queue persists when consumers disconnect
                channel.queueDeclare(queueName, true, false, false, queueArgs);
                channel.queueBind(queueName, exchange, routingKey);
                logger.debug("Declared and bound queue: {} -> {}", queueName, routingKey);
            }

            logger.info("Declared {} queues bound to exchange: {}", queueCount, exchange);
        }
    }

    // -------------------------
    // Shutdown
    // -------------------------

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

    // -------------------------
    // Getter
    // -------------------------

    /**
     * Returns the shared RabbitMQ Connection for use by ChannelPool.
     *
     * @return the active Connection instance
     */
    public Connection getConnection() {
        return connection;
    }
}