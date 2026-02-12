## Overview

This project implements a scalable, distributed WebSocket chat system with:
- WebSocket server deployed on AWS EC2
- Multithreaded load testing clients
- Performance analysis and visualization

## Repository Structure
```
cs6650-assignment1/
├── server/              # Part 1: WebSocket Server
├── client-part1/        # Part 2: Basic Load Testing Client
├── client-part2/        # Part 3: Detailed Performance Analysis Client
├── results/             # Test outputs, Per-Message Metrics CSV, Statistical Analysis and Visualization
└── README.md.           # running instructions
└── Design_Document.pdf. # Design documents and screenshots
```


## Quick Start

### 1. Server (AWS EC2)
```bash
cd server/chatflow-server
mvn clean package
java -jar target/chatflow-server.jar
```

## Important Note - AWS Academy Dynamic IP

Due to AWS Academy Learner Lab limitations, the EC2 instance's Public IP 
address changes each time the lab session restarts.

**IP used during development and testing:** 52.90.172.30

**For grading/testing:**
1. The server is deployed as a systemd service and auto-starts
2. Current IP can be found in AWS Console → EC2 → Instances
3. Update ClientConfig.java with current IP before running clients
4. Alternatively, use the provided screenshots as evidence of functionality

**Server deployment is persistent** - only the IP changes.


### 2. Client Part 1 (Basic Metrics)
```bashcd client-part1/chatflow-client
mvn clean package
java -jar target/chatflow-client-part1-jar-with-dependencies.jar
```
### 3. Client Part 2 (Detailed Metrics + Visualization)
```bashcd client-part2/chatflow-client
mvn clean package
java -jar target/chatflow-client-part2-jar-with-dependencies.jar
```
## Performance Highlights

| Metric | Client Part 1 | Client Part 2 |
|--------|--------------|--------------|
| Messages | 500,000 | 500,000 |
| Success Rate | 100% | 99.14% |
| Runtime | 3.3 sec | 3.6 min |
| Throughput | 150,693 msg/sec | 2,293 msg/sec |
| Mean Latency | N/A | 47 ms |

## Technology Stack

- **Language:** Java 17
- **Server:** Spring Boot 3.2.0, Spring WebSocket
- **Client:** Java-WebSocket 1.5.4, Apache Commons CSV, JFreeChart
- **Build:** Maven 3.9+
- **Cloud:** AWS EC2 t2.micro (us-east-1)

## Documentation

- **Design Document:** See `DESIGN_DOCUMENT.pdf`
- **Deployment Guide:** See [README.md](.server/chatflow-server/README.md)
- **Client1 Part 1 Guide"** See [README.md](/client-part1/chatflow-client/README.md) 
- **Client1 Part 2 Guide"** See [README.md](./client-part2/chatflow-client/README.md) 
- **Test Results and Analysis:** See `results/`
