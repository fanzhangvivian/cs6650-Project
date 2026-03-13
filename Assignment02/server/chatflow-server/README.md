# ChatFlow WebSocket Server

CS6650 Assignment 1 - Part 1: WebSocket Server Implementation

## Live Deployment

## Important Note - AWS Academy Dynamic IP

Due to AWS Academy Learner Lab limitations, the EC2 instance's Public IP 
address changes each time the lab session restarts.

**IP used during development and testing:** 52.90.172.30

**AWS EC2 Instance:**
- **URL:** http://52.90.172.30:8080
- **Health Check:** http://52.90.172.30:8080/health
- **WebSocket:** ws://52.90.172.30:8080/chat/{roomId}
- **Region:** us-east-1
- **Instance Type:** t2.micro

**For grading/testing:**
1. The server is deployed as a systemd service and auto-starts
2. Current IP can be found in AWS Console → EC2 → Instances
3. Update ClientConfig.java with current IP before running clients
4. Alternatively, use the provided screenshots as evidence of functionality

**Server deployment is persistent** - only the IP changes.


## Features

- WebSocket endpoint: `/chat/{roomId}`
- Message validation (userId, username, message, timestamp, messageType)
- Health check endpoint: `/health`
- Thread-safe connection management
- Comprehensive error handling

## Prerequisites
```bash
java -version  # Requires Java 17+
mvn -version   # Requires Maven 3.8+
```

## Build and Run

### Local Development
```bash
# Build
mvn clean package

# Run
java -jar target/chatflow-server.jar
```

Server starts on port 8080.

## Testing

### Health Check
```bash
curl http://52.90.172.30:8080/health
```

### WebSocket Test
```bash
# Install wscat or websocat
npm install -g wscat

# Connect
wscat -c ws://52.90.172.30:8080/chat/room1

# Send message
{"userId":"12345","username":"alice","message":"Hello","timestamp":"2026-02-10T10:30:00Z","messageType":"TEXT"}
```

## Message Format

### Valid Request
```json
{
  "userId": "12345",
  "username": "alice",
  "message": "Hello World",
  "timestamp": "2026-02-10T10:30:00Z",
  "messageType": "TEXT"
}
```

### Validation Rules
- `userId`: 1-100000
- `username`: 3-20 alphanumeric characters
- `message`: 1-500 characters
- `timestamp`: ISO-8601 format
- `messageType`: TEXT | JOIN | LEAVE

### Success Response
```json
{
  "originalMessage": {...},
  "serverTimestamp": "2026-02-10T10:30:01.123Z",
  "status": "SUCCESS",
  "roomId": "room1"
}
```

### Error Response
```json
{
  "status": "ERROR",
  "errors": ["userId must be between 1 and 100000"],
  "timestamp": "2026-02-10T10:30:01.123Z"
}
```

## Technology Stack

- Java 17
- Spring Boot 3.2.0
- Spring WebSocket
- Maven 3.9+
- AWS EC2 (Amazon Linux 2023)
