package com.chatflow.client.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to load predefined messages from resources
 */
public class MessageLoader {
    
    /**
     * Load messages from messages.txt in resources folder
     * @return List of 50 predefined messages
     */
    public static List<String> loadMessages() {
        List<String> messages = new ArrayList<>();
        
        try (InputStream is = MessageLoader.class.getClassLoader()
                .getResourceAsStream("messages.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    messages.add(line);
                }
            }
            
        } catch (IOException | NullPointerException e) {
            System.err.println("Error loading messages.txt: " + e.getMessage());
            // Return default messages if file not found
            return getDefaultMessages();
        }
        
        if (messages.isEmpty()) {
            return getDefaultMessages();
        }
        
        return messages;
    }
    
    /**
     * Fallback default messages if file loading fails
     */
    private static List<String> getDefaultMessages() {
        List<String> defaults = new ArrayList<>();
        defaults.add("Hello everyone!");
        defaults.add("How are you doing?");
        defaults.add("This is a test message");
        defaults.add("Great to be here");
        defaults.add("Anyone online?");
        return defaults;
    }
}