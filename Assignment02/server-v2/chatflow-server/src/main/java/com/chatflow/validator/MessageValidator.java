package com.chatflow.validator;

import com.chatflow.model.ChatMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MessageValidator class provides a method to validate ChatMessage objects.
 * It performs:
 * 1. Bean Validation using annotations in ChatMessage class.
 * 2. Custom validation for userId to ensure it's a valid integer within a specific range.
 * 3. Custom validation for timestamp to ensure it's in valid ISO-8601 format.
 * 4. Additional checks for message content (e.g., not empty or whitespace only).
 */
public class MessageValidator {
    
    private static final Validator validator;
    
    static {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }
    
    /**
     * Validate a ChatMessage object
     * @return List of error messages (empty list means validation passed)
     */
    public static List<String> validate(ChatMessage message) {
        List<String> errors = new ArrayList<>();
        
        // 1. Bean Validation
        Set<ConstraintViolation<ChatMessage>> violations = validator.validate(message);
        errors.addAll(violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toList()));
        
        // 2. userId 
        if (message.getUserId() != null) {
            try {
                int userId = Integer.parseInt(message.getUserId());
                if (userId < 1 || userId > 100000) {
                    errors.add("userId must be between 1 and 100000");
                }
            } catch (NumberFormatException e) {
                errors.add("userId must be a valid integer");
            }
        }
        
        // 3. ISO-8601
        if (message.getTimestamp() != null) {
            try {
                Instant.parse(message.getTimestamp());
            } catch (DateTimeParseException e) {
                errors.add("timestamp must be valid ISO-8601 format (e.g., 2026-02-08T10:30:00Z)");
            }
        }
        
        // 4. Message content validation
        if (message.getMessage() != null && message.getMessage().trim().isEmpty()) {
            errors.add("message cannot be empty or whitespace only");
        }
        
        return errors;
    }
}