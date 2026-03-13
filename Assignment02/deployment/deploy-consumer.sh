#!/bin/bash
# Deploy chatflow-consumer to EC2
# Usage: ./deploy-consumer.sh <EC2_PUBLIC_IP>

CONSUMER_IP=$1
KEY_FILE=~/.ssh/labsuser.pem
JAR_PATH=../consumer/chatflow-consumer/target/chatflow-consumer.jar

echo "Deploying consumer to $CONSUMER_IP..."
scp -i $KEY_FILE $JAR_PATH ec2-user@$CONSUMER_IP:~/chatflow-consumer.jar
ssh -i $KEY_FILE ec2-user@$CONSUMER_IP "sudo systemctl restart chatflow-consumer"
echo "Done! Health check: http://$CONSUMER_IP:8081/health"
EOF
chmod +x deployment/deploy-consumer.sh