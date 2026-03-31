#!/bin/bash
# endurance_monitor.sh
# Comprehensive per-minute snapshot during endurance test.
# Generates: endurance_monitor.txt
#
# Collects every 60 seconds:
#   - JVM heap stats (jstat -gc)
#   - System memory (free -m)
#   - Consumer /health metrics
#   - PostgreSQL connection pool state
#   - Total messages in DB
#   - Disk usage
#   - Table bloat check (n_live_tup / n_dead_tup)
#
# Usage (run on consumer EC2):
#   CONSUMER_PID=$(pgrep -f chatflow-consumer)
#   bash endurance_monitor.sh > endurance_monitor.txt 2>&1 &
#   echo $! > endurance_monitor.pid
#
# Stop:
#   kill $(cat endurance_monitor.pid)

CONSUMER_PID="${CONSUMER_PID:-$(pgrep -f chatflow-consumer)}"
CONSUMER_PORT="${CONSUMER_PORT:-8081}"
DB_HOST="${DB_HOST:-172.31.19.82}"
DB_USER="${DB_USER:-chatflow}"
DB_NAME="${DB_NAME:-chatflow}"
INTERVAL="${INTERVAL:-60}"

export PGPASSWORD="${PGPASSWORD:-chatflow123}"

echo "=== Endurance Monitor Started: $(date) ==="
echo "=== Consumer PID: $CONSUMER_PID ==="
echo "=== Monitoring interval: ${INTERVAL} seconds ==="
echo "=== PostgreSQL host: $DB_HOST ==="

while true; do
    echo ""
    echo "========================================"
    echo "=== Snapshot at: $(date) ==="
    echo "========================================"

    # JVM heap statistics
    echo ""
    echo "--- JVM Heap Statistics (S0U/S1U=Survivor, EU=Eden, OU=OldGen, FGC=FullGC) ---"
    if [ -n "$CONSUMER_PID" ]; then
        jstat -gc "$CONSUMER_PID" 1 1 2>/dev/null || echo "jstat unavailable (PID: $CONSUMER_PID)"
    else
        echo "Consumer PID not found"
    fi

    # System memory
    echo ""
    echo "--- System Memory (MB) ---"
    free -m

    # Consumer health metrics
    echo ""
    echo "--- Consumer Health Metrics ---"
    curl -s --max-time 5 "http://localhost:${CONSUMER_PORT}/health" \
        | python3 -m json.tool 2>/dev/null \
        | grep -E "messagesConsumed|bufferFullNacks|dbWritten|messagesFailed|dbCircuitBreakerState|malformedMessages|p99|p50|p95|broadcastFailed|statsAggregatorTotal|dbBufferSize" \
        || echo "Health endpoint unavailable"

    # PostgreSQL connection pool
    echo ""
    echo "--- PostgreSQL Connection Pool (active vs idle) ---"
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
        -c "SELECT state, COUNT(*) FROM pg_stat_activity WHERE datname = 'chatflow' GROUP BY state;" \
        2>/dev/null || echo "PostgreSQL unavailable"

    # Total messages in DB
    echo ""
    echo "--- Total Messages in DB ---"
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
        -c "SELECT COUNT(*) AS total_messages FROM messages;" \
        2>/dev/null

    # Disk usage
    echo ""
    echo "--- Disk Usage ---"
    df -h /
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
        -c "SELECT pg_size_pretty(pg_total_relation_size('messages')) AS table_size;" \
        2>/dev/null

    # Table bloat check
    echo ""
    echo "--- Table Bloat Check ---"
    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
        -c "SELECT relname, n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'messages';" \
        2>/dev/null

    sleep "$INTERVAL"
done
