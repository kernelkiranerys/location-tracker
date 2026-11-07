#!/usr/bin/env bash
# ============================================================
# Location Tracker — local deploy script
#
# Pushes the project to the VPS and runs the remote installer.
#
# Usage:
#   ./deploy/deploy.sh                                  # uses deploy/.env or project .env
#   ./deploy/deploy.sh root@1.2.3.4                     # explicit target
#   ./deploy/deploy.sh root@1.2.3.4 --reinstall-env     # wipes server .env too
#
# Reads DEPLOY_USER + DEPLOY_HOST from .env (or deploy/.env) if not given.
# The remote install.sh is idempotent — safe to re-run on every change.
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PKG_DIR=/tmp/location-tracker-pkg

# --- resolve target ---------------------------------------------------------
TARGET="${1:-}"
REINSTALL_ENV="${2:-}"

if [[ -z "$TARGET" ]]; then
    ENV_FILE="$PROJECT_DIR/.env"
    [[ -f "$ENV_FILE" ]] || ENV_FILE="$SCRIPT_DIR/.env"
    if [[ -f "$ENV_FILE" ]]; then
        USER_PART=$(grep -E '^DEPLOY_USER=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]"' || true)
        HOST_PART=$(grep -E '^DEPLOY_HOST=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]"' || true)
        if [[ -n "$USER_PART" && -n "$HOST_PART" ]]; then
            TARGET="${USER_PART}@${HOST_PART}"
        fi
    fi
fi

if [[ -z "$TARGET" ]]; then
    cat >&2 <<EOF
Usage: $0 [user@host]
  Set DEPLOY_USER and DEPLOY_HOST in deploy/.env, or pass target as arg.
EOF
    exit 1
fi

echo "=== Location Tracker deploy ==="
echo "  project: $PROJECT_DIR"
echo "  target : $TARGET"
echo "  remote : $PKG_DIR/"

# --- sanity: never push the local venv / db / caches / android source --------
EXCLUDES=(
    --exclude='.venv/'
    --exclude='db.sqlite3'
    --exclude='__pycache__/'
    --exclude='*.pyc'
    --exclude='staticfiles/'
    --exclude='cache/'
    --exclude='app_updates/'
    --exclude='certs/'
    --exclude='.git/'
    --exclude='.env'
    # Android app is built and distributed via the APK update pipeline,
    # not deployed to the server. Keeps the rsync payload tiny.
    --exclude='android_app/'
    # The deploy/ dir itself is copied so install.sh is on the VPS.
    # wheels/ IS shipped — the VPS has slow/flaky PyPI connectivity and
    # install.sh uses local wheels to avoid it (same pattern as codemill).
    --exclude='backups/'
    --exclude='*.md'
)

# --- 0. ensure rsync is installed on the VPS (chicken-and-egg) --------------
echo "=== Ensuring rsync on $TARGET ==="
ssh -o ConnectTimeout=10 "$TARGET" 'which rsync >/dev/null 2>&1 || sudo apt-get install -y -qq rsync' || {
    echo "ERROR: could not install rsync on $TARGET. Is sudo passwordless?" >&2
    exit 1
}

# --- 1. sync project tree to /tmp/location-tracker-pkg on the server ---------
echo "=== rsync to $TARGET:$PKG_DIR ==="
echo "  payload size:"
du -sh "$PROJECT_DIR" 2>/dev/null | sed 's/^/    /'
echo "  transferring (with progress)..."
ssh -o ConnectTimeout=10 "$TARGET" "rm -rf $PKG_DIR && mkdir -p $PKG_DIR"
rsync -av --progress --partial --timeout=60 \
    -e "ssh -o ConnectTimeout=15 -o ServerAliveInterval=10 -o ServerAliveCountMax=3" \
    "${EXCLUDES[@]}" "$PROJECT_DIR/" "$TARGET:$PKG_DIR/" \
    && echo "  rsync complete" \
    || { echo "ERROR: rsync failed (exit $?)" >&2; exit 1; }

# optional: also push the .env so it lands fresh (only when --reinstall-env)
if [[ "$REINSTALL_ENV" == "--reinstall-env" ]]; then
    if [[ -f "$PROJECT_DIR/.env" ]]; then
        echo "  pushing .env (will overwrite server .env)"
        rsync -a "$PROJECT_DIR/.env" "$TARGET:$PKG_DIR/deploy/.env.example"
    else
        echo "  WARNING: --reinstall-env given but no local .env to push" >&2
    fi
fi

# --- 2. run the remote installer --------------------------------------------
echo "=== Running remote install.sh on $TARGET ==="
ssh -o ConnectTimeout=10 "$TARGET" "sudo bash $PKG_DIR/deploy/install.sh" \
    || { echo "ERROR: install.sh failed (exit $?)" >&2; exit 1; }

echo ""
echo "=== Deploy complete ==="
echo "Visit https://tracker.example.com once DNS is pointing here."
