#!/bin/bash
# Check RabbitMQ queue depths via Management API

RABBITMQ_IP="3.87.144.112"
USER="guest"
PASS="guest"

echo "=== RabbitMQ Queue Depths ==="
for i in $(seq 1 20); do
    curl -s -u $USER:$PASS \
        http://$RABBITMQ_IP:15672/api/queues/%2F/room.$i \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(f'room.$i: {d[\"messages\"]} messages, rate: {d.get(\"message_stats\", {}).get(\"publish_details\", {}).get(\"rate\", 0):.1f}/s')
" 2>/dev/null
done
