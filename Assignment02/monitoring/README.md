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
- Server 2 health: http://98.93.245.211:8080/health
- Server 3 health: http://18.207.123.15:8080/health
- Server 4 health: http://34.227.107.5:8080/health
- Consumer health: http://34.227.65.119:8081/health
- ALB Console: AWS Console -> EC2 -> Load Balancers -> chatflow-alb