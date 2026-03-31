# Monitoring Scripts

## check-health.sh
Checks health of all server instances and consumer.
Usage: ./check-health.sh

## check-rabbitmq.sh
Checks queue depths and message rates for all 20 rooms via RabbitMQ Management API.
Usage: ./check-rabbitmq.sh

## Manual Monitoring
- RabbitMQ Console: http://3.87.144.112:15672
- Server 1 health: http://34.237.218.168:8080/health
- Consumer health: http://34.227.65.119:8081/health
- ALB Console: AWS Console -> EC2 -> Load Balancers -> chatflow-alb