package com.chatflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ErrorResponse {
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("errors")
    private List<String> errors;
    
    @JsonProperty("timestamp")
    private String timestamp;
    
    public ErrorResponse() {}
    
    public ErrorResponse(String status, List<String> errors, String timestamp) {
        this.status = status;
        this.errors = errors;
        this.timestamp = timestamp;
    }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
