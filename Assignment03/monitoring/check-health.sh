#!/bin/bash
# Check health of all ChatFlow instances

SERVER1="44.200.156.189"
CONSUMER="34.207.113.149"

echo "=== Server ==="
curl -s http://$SERVER1:8080/health | python3 -m json.tool

echo ""
echo "=== Consumer ==="
curl -s http://$CONSUMER:8081/health | python3 -m json.tool