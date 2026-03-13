#!/bin/bash
# Deploy chatflow-server-v2 to EC2
# Usage: ./deploy-server.sh <EC2_PUBLIC_IP> [server-id]

SERVER_IP=$1
SERVER_ID=${2:-server-1}
KEY_FILE=~/.ssh/labsuser.pem
JAR_PATH=../server-v2/chatflow-server/target/chatflow-server.jar

echo "Deploying to $SERVER_IP as $SERVER_ID..."
scp -i $KEY_FILE $JAR_PATH ec2-user@$SERVER_IP:~/chatflow-server.jar
ssh -i $KEY_FILE ec2-user@$SERVER_IP "sudo systemctl restart chatflow-server"
echo "Done! Health check: http://$SERVER_IP:8080/health"
EOF
chmod +x deployment/deploy-server.sh