#!/bin/bash
# Check health of all ChatFlow instances

SERVER1="54.237.57.73"
SERVER2="3.88.175.211"
CONSUMER="34.227.65.119"

echo "=== Server Instance 1 ==="
curl -s http://$SERVER1:8080/health | python3 -m json.tool

echo ""
echo "=== Server Instance 2 ==="
curl -s http://$SERVER2:8080/health | python3 -m json.tool

echo ""
echo "=== Consumer ==="
curl -s http://$CONSUMER:8081/health | python3 -m json.tool