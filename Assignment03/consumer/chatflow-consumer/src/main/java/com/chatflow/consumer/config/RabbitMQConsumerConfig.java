// package com.chatflow.consumer.config;

// import com.rabbitmq.client.Connection;
// import com.rabbitmq.client.ConnectionFactory;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Configuration;

// import jakarta.annotation.PostConstruct;
// import jakarta.annotation.PreDestroy;
// import java.io.IOException;
// import java.util.concurrent.TimeoutException;

// /**
//  * Establishes and manages the RabbitMQ connection for the Consumer application.
//  * Unlike server-v2, this config only creates the Connection.
//  * Exchange and queue declaration is handled by server-v2 on startup.
//  */
// @Configuration
// public class RabbitMQConsumerConfig {

//     private static final Logger logger = LoggerFactory.getLogger(RabbitMQConsumerConfig.class);

//     @Value("${rabbitmq.host}")
//     private String host;

//     @Value("${rabbitmq.port}")
//     private int port;

//     @Value("${rabbitmq.username}")
//     private String username;

//     @Value("${rabbitmq.password}")
//     private String password;

//     private Connection connection;

//     /**
//      * Establishes RabbitMQ connection on startup with auto-recovery enabled.
//      * Does not declare exchanges or queues - server-v2 handles infrastructure setup.
//      */
//     @PostConstruct
//     public void init() throws IOException, TimeoutException {
//         ConnectionFactory factory = new ConnectionFactory();
//         factory.setHost(host);
//         factory.setPort(port);
//         factory.setUsername(username);
//         factory.setPassword(password);

//         // Enable automatic connection recovery on network failure
//         factory.setAutomaticRecoveryEnabled(true);
//         factory.setNetworkRecoveryInterval(5000);

//         connection = factory.newConnection("chatflow-consumer");
//         logger.info("RabbitMQ connection established to {}:{}", host, port);
//     }

//     /**
//      * Closes the RabbitMQ connection gracefully on application shutdown.
//      */
//     @PreDestroy
//     public void shutdown() {
//         try {
//             if (connection != null && connection.isOpen()) {
//                 connection.close();
//                 logger.info("RabbitMQ connection closed");
//             }
//         } catch (IOException e) {
//             logger.error("Error closing RabbitMQ connection", e);
//         }
//     }

//     /**
//      * Returns the shared RabbitMQ Connection for use by MessageConsumerService.
//      *
//      * @return the active Connection instance
//      */
//     public Connection getConnection() {
//         return connection;
//     }
// }


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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Establishes and manages the RabbitMQ connection for the Consumer application.
 *
 * Key fix: sets a shared executor with multiple threads on the ConnectionFactory.
 * By default RabbitMQ Java client uses only 1 dispatch thread per connection,
 * meaning all consumer callbacks queue up on a single thread regardless of
 * how many worker threads or channels you create. Setting a thread pool here
 * allows concurrent callback execution across all channels.
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

    @Value("${rabbitmq.consumer-thread-count:20}")
    private int consumerThreadCount;

    private Connection connection;
    private ExecutorService executorService;

    /**
     * Establishes RabbitMQ connection on startup.
     * Sets a shared executor so consumer callbacks run concurrently
     * instead of serialized on a single dispatch thread.
     */
    @PostConstruct
    public void init() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        // Fix: provide a thread pool for consumer dispatch
        // Without this, all channel callbacks share 1 thread -> severe bottleneck
        executorService = Executors.newFixedThreadPool(consumerThreadCount);
        factory.setSharedExecutor(executorService);

        connection = factory.newConnection("chatflow-consumer");
        logger.info("RabbitMQ connection established to {}:{} with {} dispatch threads",
                host, port, consumerThreadCount);
    }

    /**
     * Closes connection and shuts down executor on application shutdown.
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
        if (executorService != null) {
            executorService.shutdown();
            logger.info("Executor service shut down");
        }
    }

    public Connection getConnection() {
        return connection;
    }
}