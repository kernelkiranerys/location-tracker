#!/usr/bin/env bash
# ============================================================
# Location Tracker — backup puller (run from your local machine)
#
# Connects to the VPS, dumps the SQLite DB, and downloads it.
# Safe to run on cron — names files by date, prunes old ones.
#
# Usage:
#   ./deploy/backup.sh root@YOUR.VPS.IP
#   ./deploy/backup.sh root@YOUR.VPS.IP /path/to/backups
#
# Cron example (nightly at 3am, keep 30 days):
#   0 3 * * * ./deploy/backup.sh root@vps >> /var/log/location-tracker-backup.log 2>&1
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${1:-}"
BACKUP_DIR="${2:-$PROJECT_DIR/backups}"
KEEP_DAYS=30

if [[ -z "$TARGET" ]]; then
    # Try .env for DEPLOY_USER + DEPLOY_HOST
    for ENV_FILE in "$PROJECT_DIR/.env" "$SCRIPT_DIR/.env"; do
        if [[ -f "$ENV_FILE" ]]; then
            U=$(grep -E '^DEPLOY_USER=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]"' || true)
            H=$(grep -E '^DEPLOY_HOST=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]"' || true)
            [[ -n "$U" && -n "$H" ]] && TARGET="${U}@${H}"
        fi
    done
fi

if [[ -z "$TARGET" ]]; then
    echo "Usage: $0 [user@host] [backup-dir]" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
REMOTE_DUMP="/tmp/location-tracker-backup-$STAMP.db"
LOCAL_FILE="$BACKUP_DIR/location-tracker-$STAMP.db"

echo "=== Location Tracker backup ==="
echo "  target : $TARGET"
echo "  local  : $LOCAL_FILE"

# Dump on the server (sqlite3 .backup is safe even if the DB is in use)
echo "=== Dumping remote DB ==="
ssh "$TARGET" "sqlite3 /opt/location-tracker/db.sqlite3 '.backup $REMOTE_DUMP' && echo OK"

# Pull it down
echo "=== Downloading ==="
scp "$TARGET:$REMOTE_DUMP" "$LOCAL_FILE"

# Clean up the remote temp file
ssh "$TARGET" "rm -f $REMOTE_DUMP"

# Prune old local backups
echo "=== Pruning backups older than $KEEP_DAYS days ==="
find "$BACKUP_DIR" -name 'location-tracker-*.db' -mtime +$KEEP_DAYS -print -delete 2>/dev/null || true

# Summary
COUNT=$(find "$BACKUP_DIR" -name 'location-tracker-*.db' | wc -l)
SIZE=$(du -sh "$LOCAL_FILE" | cut -f1)
echo ""
echo "=== Backup complete ==="
echo "  file   : $LOCAL_FILE ($SIZE)"
echo "  total  : $COUNT backup(s) in $BACKUP_DIR"
