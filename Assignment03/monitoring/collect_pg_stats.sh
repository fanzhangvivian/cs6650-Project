#!/bin/bash
# collect_pg_stats.sh
# Collects PostgreSQL metrics every 10 seconds during load tests.
# Generates: pg_stats_*.txt
#
# Usage:
#   bash collect_pg_stats.sh > pg_stats_baseline.txt 2>&1 &
#   echo $! > collect_pg_stats.pid
#
# Stop:
#   kill $(cat collect_pg_stats.pid)

DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USER:-chatflow}"
DB_NAME="${DB_NAME:-chatflow}"
INTERVAL="${INTERVAL:-10}"

export PGPASSWORD="${PGPASSWORD:-chatflow123}"

echo "=== PostgreSQL Stats Collection Started: $(date) ==="
echo "=== Host: $DB_HOST | DB: $DB_NAME | Interval: ${INTERVAL}s ==="

while true; do
    echo ""
    echo "=== $(date) ==="

    psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" << 'EOF'
-- Total messages persisted
SELECT COUNT(*) AS total_messages FROM messages;

-- Active vs idle connections
SELECT state, COUNT(*)
FROM pg_stat_activity
WHERE datname = 'chatflow'
GROUP BY state;

-- Index scan usage
SELECT indexrelname, idx_scan, idx_tup_read
FROM pg_stat_user_indexes
WHERE relname = 'messages'
ORDER BY idx_scan DESC;

-- Table size
SELECT
    pg_size_pretty(pg_total_relation_size('messages')) AS total_table_size,
    pg_size_pretty(pg_relation_size('messages'))       AS data_size;

-- Table bloat check
SELECT relname, seq_scan, idx_scan, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'messages';
EOF

    sleep "$INTERVAL"
done
