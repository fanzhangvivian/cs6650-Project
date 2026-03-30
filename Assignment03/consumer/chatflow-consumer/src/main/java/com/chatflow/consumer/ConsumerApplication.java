package com.chatflow.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the ChatFlow Consumer application.
 * Connects to RabbitMQ, consumes messages from room queues,
 * and broadcasts them to all server-v2 instances via HTTP.
 */
@SpringBootApplication
@EnableConfigurationProperties
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
        System.out.println("========================================");
        System.out.println("ChatFlow Consumer started successfully!");
        System.out.println("Health check: http://localhost:8081/health");
        System.out.println("========================================");
    }
}