package com.chatflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ChatFlow WebSocket Server Main Application
 */
@SpringBootApplication
public class ChatFlowServerApplication {
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  ChatFlow WebSocket Server Starting...");
        System.out.println("========================================");
        
        SpringApplication.run(ChatFlowServerApplication.class, args);
        
        System.out.println("\nServer started successfully!");
        System.out.println("WebSocket endpoint: ws://localhost:8080/chat/{roomId}");
        System.out.println("Health check: http://localhost:8080/health");
        System.out.println("========================================\n");
    }
}