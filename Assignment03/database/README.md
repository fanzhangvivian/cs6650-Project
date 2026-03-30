# ChatFlow Database — PostgreSQL Setup

## Overview

PostgreSQL is installed directly on a dedicated EC2 instance to reduce
operational overhead and conserve memory on a t2.micro instance (1GB RAM).
Running PostgreSQL as a native service avoids container overhead and keeps
more memory available for query execution and connection handling.

## Prerequisites

| Requirement | Detail |
|-------------|--------|
| EC2 OS | Amazon Linux 2023 (primary) or Ubuntu 22.04 (fallback) |
| PostgreSQL version | 15 (Amazon Linux 2023 via dnf) |
| RAM | t2.micro 1GB minimum |
| Storage | 2GB+ free (500K messages ≈ 200–400MB with indexes) |
| Security Group | Port 5432 open for inbound from consumer-v3 and server-v2 EC2 instances within VPC |

## Installation

### 1. Confirm your VPC CIDR

Before running setup.sh, confirm your VPC CIDR in AWS Console:
```
AWS Console → VPC → Your VPCs → IPv4 CIDR
```

The AWS default VPC uses `172.31.0.0/16`.
If your VPC uses a different CIDR, update this line in `setup.sh`:
```bash
VPC_CIDR="172.31.0.0/16"   # update if using custom VPC
```

### 2. Run setup script
```bash
sudo bash setup.sh
```

The script performs these steps:

| Step | Action |
|------|--------|
| 1 | Install PostgreSQL 15 via `dnf` (Amazon Linux) or `apt` (Ubuntu) |
| 2 | Run `initdb`, detect service name (`postgresql-15` or `postgresql`), start service |
| 3 | Append VPC host rule to `pg_hba.conf`, enable `listen_addresses = '*'` |
| 4 | Create user `chatflow` and database `chatflow` (idempotent) |
| 5 | Run `schema.sql` as `chatflow` user to create table and indexes |

### pg_hba.conf strategy

The script **only appends** a new VPC host rule and does not modify
existing local/peer rules. This means:

- Local management connections (`sudo -u postgres psql`) continue
  using peer auth — no password needed for admin tasks
- Application connections from consumer-v3 and server-v2 use
  the appended md5 rule over the VPC network

### systemd service name

Amazon Linux 2023 names the service `postgresql-15`.
Ubuntu names it `postgresql`.
The script detects and uses the correct name automatically.

## Verify Installation
```bash
# Check service status (use postgresql-15 on Amazon Linux 2023)
sudo systemctl status postgresql-15

# Connect and verify table structure
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow -c "\d messages"

# Verify all 5 indexes exist
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow -c "\di"
```

Expected indexes:
```
idx_event_time          → messages(event_time)
idx_room_event_time     → messages(room_id, event_time)
idx_user_event_time     → messages(user_id, event_time)
messages_message_id_key → messages(message_id)   [auto, UNIQUE]
messages_pkey           → messages(id)            [auto, PK]
```

## Connection Details

The setup script prints the JDBC URL at the end of its output.
Copy it into both application configs:

**consumer-v3/application.yml**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://<db-ec2-private-ip>:5432/chatflow
    username: chatflow
    password: chatflow123
```

**server-v2/application.yml**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://<db-ec2-private-ip>:5432/chatflow
    username: chatflow
    password: chatflow123
```

## Security Group Configuration

On the PostgreSQL EC2 instance's Security Group, add inbound rules:

| Type | Protocol | Port | Source |
|------|----------|------|--------|
| Custom TCP | TCP | 5432 | Security Group ID of consumer-v3 EC2 |
| Custom TCP | TCP | 5432 | Security Group ID of server-v2 EC2 |

Use Security Group IDs as the source (not IP ranges) so the rule
remains valid even if EC2 instances are restarted with new IPs.

Do **not** open port 5432 to `0.0.0.0/0`.

## Reset Database (Development Only)
```bash
# Option 1: Drop and recreate table only
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -c "DROP TABLE IF EXISTS messages;"
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -f schema.sql

# Option 2: Full reset (drop and recreate database)
sudo -u postgres psql -c "DROP DATABASE IF EXISTS chatflow;"
sudo -u postgres psql -c "CREATE DATABASE chatflow OWNER chatflow;"
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -f schema.sql
```

## Monitoring During Load Tests
```bash
# Message count (run from PostgreSQL EC2)
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -c "SELECT COUNT(*) FROM messages;"

# Active connections by state
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -c "SELECT state, COUNT(*) FROM pg_stat_activity WHERE datname='chatflow' GROUP BY state;"

# Table size on disk
PGPASSWORD=chatflow123 psql -h localhost -U chatflow -d chatflow \
  -c "SELECT pg_size_pretty(pg_total_relation_size('messages'));"

# Run full monitoring script (from Part 4)
bash ../monitoring/collect_pg_stats.sh
```