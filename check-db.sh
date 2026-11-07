#!/usr/bin/env bash
# Inspect the live location-tracker SQLite DB on this host.
# Usage:
#   ./check-db.sh            # uses sudo (default on the NixOS box)
#   ./check-db.sh --no-sudo  # if running as the locationtracker user already
set -euo pipefail

DB="${DB:-/opt/location-tracker/db.sqlite3}"

if [[ "${1:-}" == "--no-sudo" ]]; then
  SUDO=""
else
  SUDO="sudo"
fi

if [[ ! -f "$DB" ]] && [[ -z "$SUDO" ]]; then
  echo "DB not found at $DB" >&2; exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "sqlite3 not in PATH — running via nix-shell (this may take a moment on first run)..."
fi

# Run sqlite3 against $DB. Falls back to nix-shell -p sqlite when sqlite3 is
# not installed system-wide (common on NixOS minimal installs). SQL is written
# to a temp file and read via stdin to avoid shell-quoting issues with
# dot-commands and newlines.
sql() {
  local input="$1"
  local tmp
  tmp=$(mktemp)
  printf '%s\n' "$input" > "$tmp"
  if command -v sqlite3 >/dev/null 2>&1; then
    $SUDO sqlite3 "$DB" < "$tmp"
  else
    $SUDO nix-shell -p sqlite --run "sqlite3 '$DB' < '$tmp'"
  fi
  rm -f "$tmp"
}

echo "=== Location-tracker DB: $DB ==="
echo
echo "--- File size ---"
$SUDO ls -lh "$DB"
echo

echo "--- Overall page stats ---"
sql ".headers on
.mode column
SELECT
  page_size AS page_bytes,
  page_count,
  page_size * page_count AS db_bytes,
  printf('%.2f MB', page_size * page_count / 1048576.0) AS db_mb
FROM pragma_page_size(), pragma_page_count();"
echo

echo "--- Row counts for each tracker table ---"
# Generate one COUNT(*) per table, executed as a single statement.
tables_sql="SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'tracker\_%' ESCAPE '\\' ORDER BY name;"
table_list=$(command -v sqlite3 >/dev/null 2>&1 \
  && $SUDO sqlite3 "$DB" "$tables_sql" \
  || $SUDO nix-shell -p sqlite --run "sqlite3 '$DB' \"$tables_sql\"")
union_sql=".headers on
.mode column
"
first=1
for t in $table_list; do
  if [[ $first -eq 1 ]]; then
    union_sql+="SELECT '$t' AS table_name, COUNT(*) AS rows FROM \"$t\""
    first=0
  else
    union_sql+="
UNION ALL SELECT '$t', COUNT(*) FROM \"$t\""
  fi
done
union_sql+="
ORDER BY rows DESC;"
sql "$union_sql"
echo

echo "--- Per-table storage (KB, incl. indexes) — top 15 ---"
sql ".headers on
.mode column
SELECT
  name AS table_or_index,
  SUM(pgsize) / 1024 AS kb,
  printf('%.2f MB', SUM(pgsize) / 1048576.0) AS mb
FROM dbstat
WHERE schema = 'main'
GROUP BY name
ORDER BY SUM(pgsize) DESC
LIMIT 15;"
echo

echo "--- AppLog: latest and oldest entries ---"
sql ".headers on
.mode column
SELECT
  MIN(received_at) AS oldest,
  MAX(received_at) AS latest,
  COUNT(*)         AS total
FROM tracker_applog;"
echo

echo "--- AppLog: rows older than 7 / 30 / 90 days ---"
sql ".headers on
.mode column
SELECT
  COUNT(*) FILTER (WHERE received_at < datetime('now','-7 days'))  AS older_7d,
  COUNT(*) FILTER (WHERE received_at < datetime('now','-30 days')) AS older_30d,
  COUNT(*) FILTER (WHERE received_at < datetime('now','-90 days')) AS older_90d
FROM tracker_applog;"
echo

echo "Done. If applog_rows is large (>100k) or db_mb is >50MB, prune before migrating:"
echo "  sudo nix-shell -p sqlite --run 'sqlite3 $DB \"DELETE FROM tracker_applog WHERE received_at < datetime(\\\"now\\\",\\\"-30 days\\\"); VACUUM;\"'"
