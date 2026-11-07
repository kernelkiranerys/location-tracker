#!/usr/bin/env bash
# ============================================================
# Location Tracker — remote installer
# Idempotent: safe to re-run on every deploy.
#
# Runs alongside CodeMill (port 8765) and JCBurns (port 8766) on the
# same VPS. Uses port 8767.
#
# Result on the VPS:
#   /opt/location-tracker/              app directory (synced each deploy)
#   /opt/location-tracker/.venv/         python venv (rebuilt each deploy)
#   /opt/location-tracker/.env           env file (PRESERVED across deploys)
#   /opt/location-tracker/db.sqlite3     database (PRESERVED)
#   /opt/location-tracker/app_updates/   APK + version.json (PRESERVED)
#   /opt/location-tracker/certs/        self-signed cert for legacy vhost
#   /opt/location-tracker/cache/         file-based cache
#   /etc/systemd/system/locationtracker.service
#   /etc/caddy/sites/location-tracker.caddy (two vhosts: new + legacy)
# ============================================================

set -euo pipefail

APP_DIR=/opt/location-tracker
VENV=$APP_DIR/.venv
PKG_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE_USER=locationtracker
DOMAIN="${TRACKER_DOMAIN:-tracker.example.com}"
LEGACY_HOST="${TRACKER_LEGACY_HOST:-tracker.example.com}"
LEGACY_PORT="${TRACKER_LEGACY_PORT:-8443}"

if [[ $EUID -ne 0 ]]; then
    echo "Run as root (or with sudo)." >&2
    exit 1
fi

echo "=== Location Tracker installer ==="
echo "  pkg dir : $PKG_DIR"
echo "  app dir : $APP_DIR"
echo "  domain  : $DOMAIN"
echo "  legacy  : $LEGACY_HOST:$LEGACY_PORT"

# ----------------------------------------------------------------
# 1. System packages (most installed already by CodeMill)
# ----------------------------------------------------------------
echo "=== Installing system packages ==="
export DEBIAN_FRONTEND=noninteractive
apt-get -qq update
apt-get -qq install -y --no-install-recommends \
    python3 python3-venv python3-dev python3-pip \
    gunicorn \
    ca-certificates curl rsync \
    sqlite3

# ----------------------------------------------------------------
# 2. Open the legacy port in ufw (added by CodeMill: 22/80/443 only)
# ----------------------------------------------------------------
echo "=== Ensuring firewall allows legacy port $LEGACY_PORT ==="
if ufw status | grep -q 'Status: active'; then
    if ! ufw status | grep -qE "\\b$LEGACY_PORT/tcp\\b"; then
        ufw allow "$LEGACY_PORT/tcp"
        echo "  opened $LEGACY_PORT/tcp"
    else
        echo "  $LEGACY_PORT/tcp already allowed"
    fi
else
    echo "  ufw not active — skipping (CodeMill install enables it)"
fi

# ----------------------------------------------------------------
# 3. Service user + app directory
# ----------------------------------------------------------------
echo "=== Ensuring service user '$SERVICE_USER' ==="
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

echo "=== Setting up $APP_DIR ==="
mkdir -p "$APP_DIR" "$APP_DIR/staticfiles" "$APP_DIR/cache" \
         "$APP_DIR/app_updates" "$APP_DIR/certs"

# .env: create from example on first install only
if [[ ! -f "$APP_DIR/.env" ]]; then
    if [[ -f "$PKG_DIR/deploy/.env.example" ]]; then
        cp "$PKG_DIR/deploy/.env.example" "$APP_DIR/.env"
        echo "  created $APP_DIR/.env from .env.example"
        echo "  >>> EDIT $APP_DIR/.env before going live"
    else
        echo "ERROR: $PKG_DIR/deploy/.env.example not found" >&2
        exit 1
    fi
else
    echo "  existing $APP_DIR/.env preserved"
fi

# Auto-generate a strong SECRET_KEY if the .env still has the placeholder
if grep -q '^DJANGO_SECRET_KEY=replace-me-with-a-long-random-string$' "$APP_DIR/.env"; then
    NEW_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(50))')"
    sed -i "s|^DJANGO_SECRET_KEY=.*|DJANGO_SECRET_KEY=$NEW_KEY|" "$APP_DIR/.env"
    echo "  generated a fresh DJANGO_SECRET_KEY"
fi

# ----------------------------------------------------------------
# 4. Self-signed cert for the legacy vhost
#    Existing installed APKs trust ONLY the specific cert baked into
#    R.raw.server_cert. The operator must copy cert.pem + key.pem
#    from the home box (e.g. /etc/caddy/certs/) to
#    /opt/location-tracker/certs/ — install.sh will not generate one.
# ----------------------------------------------------------------
echo "=== Checking legacy self-signed cert ==="
CERT_FILE="$APP_DIR/certs/cert.pem"
KEY_FILE="$APP_DIR/certs/key.pem"
if [[ ! -f "$CERT_FILE" || ! -f "$KEY_FILE" ]]; then
    echo "  WARNING: $CERT_FILE and/or $KEY_FILE missing."
    echo "           The legacy vhost ($LEGACY_HOST:$LEGACY_PORT) will NOT start."
    echo "           Copy them from the home NixOS box:"
    echo "             sudo rsync -avz /etc/caddy/certs/cert.pem root@VPS:$CERT_FILE"
    echo "             sudo rsync -avz /etc/caddy/certs/key.pem  root@VPS:$KEY_FILE"
    echo "           Then re-run this installer."
    HAS_LEGACY_CERT=0
else
    echo "  cert + key present"
    HAS_LEGACY_CERT=1
fi

# ----------------------------------------------------------------
# 5. Sync application code
# ----------------------------------------------------------------
echo "=== Syncing application code ==="
rsync -a --delete \
    --exclude='.venv/' \
    --exclude='.env' \
    --exclude='staticfiles/' \
    --exclude='cache/' \
    --exclude='app_updates/' \
    --exclude='certs/' \
    --exclude='db.sqlite3' \
    --exclude='google_api_key' \
    --exclude='__pycache__/' \
    --exclude='*.pyc' \
    --exclude='.git/' \
    --exclude='wheels/' \
    --exclude='android_app/' \
    --exclude='*.md' \
    --exclude='deploy/' \
    "$PKG_DIR/" "$APP_DIR/"

chown -R "$SERVICE_USER":"$SERVICE_USER" "$APP_DIR"
chmod 600 "$APP_DIR/.env"
# Certs must be readable by caddy. The caddy user reads them at startup.
chmod 644 "$CERT_FILE" 2>/dev/null || true
chmod 640 "$KEY_FILE" 2>/dev/null || true

# ----------------------------------------------------------------
# 6. Python virtualenv + dependencies (from local wheels if present)
# ----------------------------------------------------------------
echo "=== Building virtualenv ==="
# Always recreate the venv so we never inherit a broken/partial state from a
# failed previous run. Wheels make the rebuild cheap.
rm -rf "$VENV"
python3 -m venv "$VENV"
# NOTE: do NOT run "pip install --upgrade pip" here — that hits PyPI, which is
# slow/flaky on this VPS. The venv's bundled pip is fine for local wheels.
# pip only gets upgraded in the PyPI fallback path below if wheels are missing.

if [[ -d "$PKG_DIR/wheels" && -n "$(ls -A $PKG_DIR/wheels 2>/dev/null)" ]]; then
    echo "  installing from local wheels"
    "$VENV/bin/pip" install --quiet --no-index --find-links="$PKG_DIR/wheels" \
        Django gunicorn djangorestframework 2>&1 || {
        echo "  local wheels incomplete, falling back to PyPI" >&2
        "$VENV/bin/pip" install --quiet --upgrade --timeout 120 --retries 5 pip
        "$VENV/bin/pip" install --quiet --timeout 120 --retries 5 \
            -r "$APP_DIR/requirements.txt"
    }
else
    echo "  no local wheels, using PyPI (may be slow on this VPS)"
    "$VENV/bin/pip" install --quiet --upgrade --timeout 120 --retries 5 pip
    "$VENV/bin/pip" install --quiet --timeout 120 --retries 5 \
        -r "$APP_DIR/requirements.txt"
fi

# ----------------------------------------------------------------
# 7. Django housekeeping
# ----------------------------------------------------------------
echo "=== Django setup ==="
set -a
. "$APP_DIR/.env"
set +a
export DJANGO_SETTINGS_MODULE=location_server.settings

"$VENV/bin/python" "$APP_DIR/manage.py" migrate --no-input
"$VENV/bin/python" "$APP_DIR/manage.py" collectstatic --noinput --clear

# ----------------------------------------------------------------
# 8. systemd unit (port 8767, alongside CodeMill 8765 + JCBurns 8766)
# ----------------------------------------------------------------
echo "=== Writing systemd unit ==="
cat > /etc/systemd/system/locationtracker.service <<EOF
[Unit]
Description=Location Tracker (Django + gunicorn)
After=network.target

[Service]
Type=notify
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
Environment=DJANGO_SETTINGS_MODULE=location_server.settings
Environment=APK_DIR=$APP_DIR/app_updates
ExecStart=$VENV/bin/gunicorn \\
    --bind 127.0.0.1:8767 \\
    --workers 2 \\
    --timeout 30 \\
    --access-logfile - \\
    --error-logfile - \\
    location_server.wsgi:application
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable locationtracker >/dev/null 2>&1 || true

# ----------------------------------------------------------------
# 9. Caddy site (auto-HTTPS for the new domain; self-signed legacy)
# ----------------------------------------------------------------
echo "=== Writing Caddy site ==="
mkdir -p /etc/caddy/sites /var/log/caddy

ADMIN_URL="${DJANGO_ADMIN_URL:-staff-control}"
ADMIN_IPS="${ADMIN_ALLOWED_IPS:-}"
ADMIN_AUTH_USER="${ADMIN_BASIC_AUTH_USER:-admin}"
ADMIN_AUTH_PASS="${ADMIN_BASIC_AUTH_PASS:-}"

# Build the admin protection block if basic auth password is set.
# Uses real newlines so Caddy sees a proper block (brace on its own line).
AUTH_BLOCK=""
if [[ -n "$ADMIN_AUTH_PASS" ]]; then
    HASH=$(caddy hash-password --plaintext "$ADMIN_AUTH_PASS")
    AUTH_BLOCK=$(printf 'basicauth {\n    %s %s\n}' "$ADMIN_AUTH_USER" "$HASH")
    echo "  admin basic auth enabled for user '$ADMIN_AUTH_USER'"
fi

# Build the IP allowlist matcher if IPs are set
IP_MATCHER=""
if [[ -n "$ADMIN_IPS" ]]; then
    # Convert comma-separated to space-separated for Caddy remote_ip
    IPS_SPACED=$(echo "$ADMIN_IPS" | tr ',' ' ')
    # Use remote_ip (works on Caddy 2.6+; client_ip is 2.7+ only)
    IP_MATCHER="@admin_ip remote_ip $IPS_SPACED"
    echo "  admin IP allowlist: $IPS_SPACED"
fi

# Helper: emit the admin-protected block for a vhost.
emit_admin_block() {
    if [[ -n "$IP_MATCHER" ]]; then
        echo "        $IP_MATCHER"
        echo "        handle @admin_ip {"
        if [[ -n "$AUTH_BLOCK" ]]; then
            echo "            $AUTH_BLOCK"
        fi
        echo "            reverse_proxy 127.0.0.1:8767"
        echo "        }"
        echo "        respond 403"
    else
        if [[ -n "$AUTH_BLOCK" ]]; then
            echo "        $AUTH_BLOCK"
        fi
        echo "        reverse_proxy 127.0.0.1:8767"
    fi
}

# Generate the Caddy site file. Two vhosts:
#   1. $DOMAIN — Let's Encrypt auto-HTTPS, port 443 (new clients)
#   2. $LEGACY_HOST:$LEGACY_PORT — self-signed cert (existing APKs)
SITE_FILE=/etc/caddy/sites/location-tracker.caddy
{
    echo "# Managed by location-tracker install.sh — regenerate on deploy."
    echo ""

    # --- New vanity vhost (Let's Encrypt) ---
    echo "$DOMAIN {"
    echo "    encode zstd gzip"
    echo ""
    echo "    @admin path /$ADMIN_URL /$ADMIN_URL/*"
    echo "    handle @admin {"
    emit_admin_block
    echo "    }"
    echo ""
    echo "    handle {"
    echo "        reverse_proxy 127.0.0.1:8767"
    echo "    }"
    echo ""
    echo "    handle_path /static/* {"
    echo "        root * $APP_DIR/staticfiles"
    echo "        file_server"
    echo "    }"
    echo ""
    echo "    log {"
    echo "        output file /var/log/caddy/location-tracker.log"
    echo "    }"
    echo "}"
    echo ""

    # --- Legacy vhost (self-signed cert) ---
    if [[ "$HAS_LEGACY_CERT" -eq 1 ]]; then
        echo "# Legacy vhost — installed APKs trust this self-signed cert."
        echo "$LEGACY_HOST:$LEGACY_PORT {"
        echo "    tls $CERT_FILE $KEY_FILE"
        echo "    encode zstd gzip"
        echo ""
        echo "    @admin path /$ADMIN_URL /$ADMIN_URL/*"
        echo "    handle @admin {"
        emit_admin_block
        echo "    }"
        echo ""
        echo "    handle {"
        echo "        reverse_proxy 127.0.0.1:8767"
        echo "    }"
        echo ""
        echo "    handle_path /static/* {"
        echo "        root * $APP_DIR/staticfiles"
        echo "        file_server"
        echo "    }"
        echo ""
        echo "    log {"
        echo "        output file /var/log/caddy/location-tracker-legacy.log"
        echo "    }"
        echo "}"
    else
        echo "# Legacy vhost ($LEGACY_HOST:$LEGACY_PORT) SKIPPED — cert missing."
        echo "# Copy cert.pem + key.pem to $APP_DIR/certs/ and re-run install.sh."
    fi
} > "$SITE_FILE"

# Initialise main Caddyfile to import sites if not already (CodeMill does this too)
if [[ ! -f /etc/caddy/Caddyfile ]] || ! grep -q 'sites/\*.caddy' /etc/caddy/Caddyfile; then
    cat > /etc/caddy/Caddyfile <<EOF
# Managed Caddyfile — import individual site configs.
import sites/*.caddy
EOF
fi

chown -R caddy:caddy /var/log/caddy 2>/dev/null || true
systemctl enable caddy >/dev/null 2>&1 || true
systemctl reload caddy 2>/dev/null || systemctl restart caddy || true

# ----------------------------------------------------------------
# 10. Restart the app
# ----------------------------------------------------------------
echo "=== Restarting services ==="
systemctl restart locationtracker
sleep 2
systemctl --no-pager --full status locationtracker | head -15 || true

# ----------------------------------------------------------------
# Done — print next steps
# ----------------------------------------------------------------
VPS_IP=$(curl -s ifconfig.me 2>/dev/null || echo "<your-vps-ip>")

echo ""
echo "============================================================"
echo "  INSTALL COMPLETE"
echo "============================================================"
echo "  site (new)   : https://$DOMAIN"
if [[ "$HAS_LEGACY_CERT" -eq 1 ]]; then
    echo "  site (legacy): https://$LEGACY_HOST:$LEGACY_PORT"
else
    echo "  site (legacy): SKIPPED — copy cert + key to $APP_DIR/certs/ and re-run"
fi
echo "  app          : systemctl status locationtracker"
echo "  proxy        : systemctl status caddy"
echo ""
echo "--- DNS RECORDS TO ADD ---"
echo "  A     $DOMAIN.   -> $VPS_IP"
echo "  A     $LEGACY_HOST.  -> $VPS_IP   (only when ready to cut over from home box)"
echo ""
echo "--- NEXT STEPS ---"
echo "  1. Edit $APP_DIR/.env:"
echo "     - ADMIN_ALLOWED_IPS=your.home.ip (comma-separated)"
echo "     - ADMIN_BASIC_AUTH_PASS=a-strong-password"
echo "     - DJANGO_ADMIN_URL=choose-a-secret-path (no slashes)"
echo "  2. Copy the self-signed cert from the home NixOS box:"
echo "       sudo rsync -avz /etc/caddy/certs/cert.pem root@VPS:$CERT_FILE"
echo "       sudo rsync -avz /etc/caddy/certs/key.pem  root@VPS:$KEY_FILE"
echo "  3. systemctl restart locationtracker && bash $PKG_DIR/deploy/install.sh"
echo "  4. Migrate the DB:"
echo "       sudo systemctl stop locationtracker   # on home box"
echo "       sudo rsync -avz /opt/location-tracker/db.sqlite3  root@VPS:$APP_DIR/"
echo "       sudo chown $SERVICE_USER:$SERVICE_USER $APP_DIR/db.sqlite3"
echo "  5. Add DNS records above."
echo "  6. Flip the DuckDNS A record for $LEGACY_HOST to \$VPS_IP"
echo "     (the installed APKs will start hitting this VPS within 20 min)."
