# Deployment Configuration

## AWS Infrastructure

| Component | Instance Type | Public IP | Private IP |
|---|---|---|---|
| server-v2 Instance 1 | t3.micro | 34.237.218.168 | 172.31.12.21 |
| server-v2 Instance 2 | t3.micro | 98.93.245.211 | 172.31.24.202 |
| server-v2 Instance 3 | t3.micro | 18.207.123.15 | 172.31.18.197 |
| server-v2 Instance 4 | t3.micro | 34.227.107.53 | 172.31.28.227 |
| RabbitMQ | t2.micro | 3.87.144.112 | 172.31.27.132 |
| Consumer | t2.micro | 34.227.65.119 | 172.31.25.161 |

## ALB Configuration
- DNS: chatflow-alb-1683935178.us-east-1.elb.amazonaws.com
- Idle timeout: 300 seconds
- Health check path: /health
- Health check interval: 30 seconds
- Healthy threshold: 2
- Unhealthy threshold: 3
- Sticky sessions: enabled (1 day)

## Security Groups
- chatflow-sg: ports 22, 80, 8080, 8081
- rabbitmq-sg: port 22 (my IP), 5672 (chatflow-sg only), 15672 (my IP)

## RabbitMQ Configuration
- Exchange: chat.exchange (topic, durable)
- Queues: room.1 to room.20 (durable)
- Message TTL: 3600000ms (1 hour)
- Max queue length: 100000 messages

## Deployment Steps
1. SSH into EC2 instance
2. Upload jar: scp -i ~/.ssh/labsuser.pem chatflow-server.jar ec2-user@<IP>:~/
3. Configure systemd: sudo systemctl enable chatflow-server
4. Start service: sudo systemctl start chatflow-server