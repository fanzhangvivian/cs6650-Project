#!/bin/bash
# =============================================================================
# CS6650 Assignment 3
# File: database/setup.sh
#
# Purpose:
#   Install PostgreSQL on EC2 (Amazon Linux 2023 primary, Ubuntu fallback),
#   create the chatflow user and database,
#   and run schema.sql to set up the messages table and indexes.
#
# Usage:
#   sudo bash setup.sh
#
# Prerequisites:
#   - Run on the EC2 instance designated for PostgreSQL
#   - schema.sql must be in the same directory as this script
#   - Update VPC_CIDR below if not using AWS default VPC (172.31.0.0/16)
#   - Port 5432 must be open in Security Group for VPC internal access
# =============================================================================

set -e  # Exit immediately on any error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_NAME="chatflow"
DB_USER="chatflow"
DB_PASS="chatflow123"

# Update this if your VPC uses a custom CIDR block.
# AWS default VPC is 172.31.0.0/16.
# Check: AWS Console → VPC → Your VPCs → IPv4 CIDR
VPC_CIDR="172.31.0.0/16"

echo "=========================================="
echo " ChatFlow PostgreSQL Setup"
echo " Target OS : Amazon Linux 2023 / Ubuntu"
echo " VPC CIDR  : $VPC_CIDR"
echo "=========================================="

# ── Step 1: Install PostgreSQL ─────────────────────────────────────────────────

echo ""
echo "[1/5] Installing PostgreSQL..."

if command -v dnf &> /dev/null; then
    # Amazon Linux 2023
    sudo dnf install -y postgresql15-server postgresql15
    echo "      Installed via dnf (Amazon Linux 2023)"

elif command -v apt &> /dev/null; then
    # Ubuntu fallback
    sudo apt update -y
    sudo apt install -y postgresql postgresql-contrib
    echo "      Installed via apt (Ubuntu)"

else
    echo "ERROR: Unsupported OS. Install PostgreSQL manually then rerun."
    exit 1
fi

# ── Step 2: Initialize and start PostgreSQL ────────────────────────────────────

echo ""
echo "[2/5] Initializing and starting PostgreSQL..."

if command -v dnf &> /dev/null; then
    # Amazon Linux 2023 requires explicit initdb before first start
    sudo postgresql-setup --initdb 2>/dev/null \
        || echo "      (initdb already done, skipping)"
fi

# Detect service name: Amazon Linux 2023 uses postgresql-15, Ubuntu uses postgresql
if systemctl list-unit-files | grep -q "^postgresql-15.service"; then
    SERVICE_NAME="postgresql-15"
else
    SERVICE_NAME="postgresql"
fi

echo "      Using systemd service: $SERVICE_NAME"
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl start "$SERVICE_NAME"
echo "      PostgreSQL service started"

# ── Step 3: Configure pg_hba.conf and postgresql.conf ─────────────────────────
#
# Strategy:
#   - Do NOT modify existing local/peer rules (avoids sed fragility across versions)
#   - Only APPEND a new host rule for VPC CIDR with md5 authentication
#   - Local management connections (sudo -u postgres psql) keep using peer auth
#   - Application connections from consumer-v3 / server-v2 use the new md5 rule
#   - Enable listen_addresses = '*' so PostgreSQL accepts non-localhost connections

echo ""
echo "[3/5] Configuring pg_hba.conf for VPC access..."

# Get config file paths from PostgreSQL itself (version-agnostic)
PG_HBA=$(sudo -u postgres psql -t -c "SHOW hba_file;" 2>/dev/null | tr -d ' ')
PG_CONF=$(sudo -u postgres psql -t -c "SHOW config_file;" 2>/dev/null | tr -d ' ')

echo "      pg_hba.conf : $PG_HBA"
echo "      postgresql.conf : $PG_CONF"

# Backup original configs before any modification
sudo cp "$PG_HBA"  "${PG_HBA}.bak"
sudo cp "$PG_CONF" "${PG_CONF}.bak"
echo "      Backups created: ${PG_HBA}.bak, ${PG_CONF}.bak"

# Append VPC host rule only — do not touch existing local/peer lines
# This rule allows consumer-v3 and server-v2 to connect with password
echo "host    all             all             $VPC_CIDR               md5" \
    | sudo tee -a "$PG_HBA" > /dev/null
echo "      Appended VPC host rule to pg_hba.conf"

# Allow PostgreSQL to accept connections from outside localhost
# sed targets the commented-out default line; safe to run multiple times
sudo sed -i "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" "$PG_CONF"
echo "      listen_addresses set to '*'"

# Restart to apply both config changes
sudo systemctl restart "$SERVICE_NAME"
echo "      PostgreSQL restarted with new configuration"

# ── Step 4: Create user and database ──────────────────────────────────────────

echo ""
echo "[4/5] Creating user '$DB_USER' and database '$DB_NAME'..."

# Uses peer auth via sudo -u postgres (no password needed, not affected by Step 3)
sudo -u postgres psql << EOF
-- Create application user (idempotent)
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT FROM pg_catalog.pg_roles WHERE rolname = '$DB_USER'
    ) THEN
        CREATE USER $DB_USER WITH PASSWORD '$DB_PASS';
        RAISE NOTICE 'User $DB_USER created';
    ELSE
        ALTER USER $DB_USER WITH PASSWORD '$DB_PASS';
        RAISE NOTICE 'User $DB_USER already exists, password updated';
    END IF;
END
\$\$;

-- Create database (idempotent)
SELECT 'CREATE DATABASE $DB_NAME OWNER $DB_USER'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = '$DB_NAME'
)\gexec

-- Grant all privileges
GRANT ALL PRIVILEGES ON DATABASE $DB_NAME TO $DB_USER;
EOF

echo "      User '$DB_USER' and database '$DB_NAME' ready"

# ── Step 5: Run schema.sql ────────────────────────────────────────────────────

echo ""
echo "[5/5] Running schema.sql..."

SCHEMA_FILE="$SCRIPT_DIR/schema.sql"

if [ ! -f "$SCHEMA_FILE" ]; then
    echo "ERROR: schema.sql not found at $SCHEMA_FILE"
    echo "       Ensure schema.sql is in the same directory as setup.sh"
    exit 1
fi

# Run as chatflow user so table owner = chatflow
# No extra GRANT needed for INSERT/SELECT by the application user
PGPASSWORD="$DB_PASS" psql \
    -h localhost \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -f "$SCHEMA_FILE"

echo "      schema.sql executed successfully"

# ── Verify ────────────────────────────────────────────────────────────────────

echo ""
echo "[Verify] Table structure and indexes..."
echo ""

PGPASSWORD="$DB_PASS" psql -h localhost -U "$DB_USER" -d "$DB_NAME" << EOF
\d messages
\di
EOF

# ── Summary ───────────────────────────────────────────────────────────────────

PRIVATE_IP=$(hostname -I | awk '{print $1}')

echo ""
echo "=========================================="
echo " Setup Complete"
echo "=========================================="
echo ""
echo "  Host     : $PRIVATE_IP"
echo "  Port     : 5432"
echo "  Database : $DB_NAME"
echo "  User     : $DB_USER"
echo "  Password : $DB_PASS"
echo ""
echo "  JDBC URL :"
echo "  jdbc:postgresql://$PRIVATE_IP:5432/$DB_NAME"
echo ""
echo " Next steps:"
echo "  1. Copy the JDBC URL into consumer-v3/application.yml"
echo "  2. Copy the JDBC URL into server-v2/application.yml"
echo "  3. Verify Security Group allows port 5432 inbound"
echo "     from consumer-v3 and server-v2 EC2 Security Groups"
echo "=========================================="