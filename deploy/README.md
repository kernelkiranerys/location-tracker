# Location Tracker — deployment

Fully automated, idempotent. Push from your machine; the script SSHes in,
syncs the project, and runs the installer on the VPS.

Runs alongside The Code Mill (port 8765) and J C Burns (port 8766) on the
same VPS, sharing one Caddy. The tracker uses port 8767.

## Scripts

| Script | Where it runs | What it does |
|---|---|---|
| `deploy/deploy.sh` | **local** | rsync project to VPS, SSH in, run install.sh |
| `deploy/install.sh` | **VPS** (as root) | Install everything, configure services |
| `deploy/backup.sh` | **local** | Pull a DB dump from the VPS |

## One-time setup

These steps assume CodeMill's `install.sh` has already hardened the VPS
(ufw, fail2ban, unattended-upgrades, swap, Caddy). This installer only
adds tracker-specific things on top.

1. **Point DNS** for `tracker.example.com` (or your chosen subdomain) at the VPS IP.
2. **SSH key access** to root on the VPS.
3. **Copy the self-signed cert** from the home NixOS box to the VPS
   (one-time — existing installed APKs trust this specific cert):
   ```bash
   sudo rsync -avz /etc/caddy/certs/cert.pem root@VPS_IP:/opt/location-tracker/certs/cert.pem
   sudo rsync -avz /etc/caddy/certs/key.pem  root@VPS_IP:/opt/location-tracker/certs/key.pem
   ```
   (Create `/opt/location-tracker/certs/` first if it doesn't exist yet —
   `install.sh` will create it on first run.)

## First deploy

```bash
cd ~/Scripts/location_server
./deploy/deploy.sh root@YOUR.VPS.IP
```

The installer:
- installs Python, gunicorn, sqlite3 (most already present from CodeMill)
- opens port 8443 in ufw (for the legacy vhost)
- creates a `locationtracker` system user + `/opt/location-tracker/` app dir
- copies `.env.example` → `.env` **on first install only** (preserved across deploys)
- auto-generates a strong `DJANGO_SECRET_KEY`
- builds venv, runs migrations + collectstatic
- writes systemd unit (gunicorn on 127.0.0.1:8767, 2 workers)
- writes Caddy site with **two vhosts**:
  - `tracker.example.com` → Let's Encrypt auto-HTTPS → 127.0.0.1:8767
  - `tracker.example.com:8443` → self-signed cert → 127.0.0.1:8767 (legacy)
- prints DNS records to add

After the first deploy, **edit `/opt/location-tracker/.env`** on the VPS:
```bash
ssh root@YOUR.VPS.IP
nano /opt/location-tracker/.env
```
Set at least:
- `ADMIN_ALLOWED_IPS` — your home IP (find at https://ifconfig.me)
- `ADMIN_BASIC_AUTH_PASS` — a strong password for the admin panel
- `DJANGO_ADMIN_URL` — a secret path (no slashes)
- `DJANGO_ALLOWED_HOSTS` — already includes both domains by default
- `DJANGO_CSRF_TRUSTED_ORIGINS` — already includes both vhosts by default
- `TRACKER_SERVER_HOST` / `TRACKER_SERVER_PORT` — leave as the legacy values
  until the APK upgrade ships; flip to the new domain afterwards

Then re-run the installer to apply the new env values:
```bash
bash /tmp/location-tracker-pkg/deploy/install.sh
```

## DNS records (add at your registrar)

```
A     tracker.example.com.   -> YOUR.VPS.IP
A     tracker.example.com.     -> YOUR.VPS.IP   (only when cutting over from home)
```

The `tracker.example.com` record is managed at DuckDNS, not a regular
registrar. Update it at https://www.duckdns.org only when ready to flip
traffic from the home box to the VPS.

## Migration from the home NixOS box (zero service loss)

The full pipeline is in the conversation notes; the short version:

1. Deploy the tracker to the VPS (above). The new vhost works immediately.
   The legacy vhost works once the cert is in place.
2. Stop the home service: `sudo systemctl stop location-tracker`
3. Copy the live DB:
   ```bash
   sudo rsync -avz /opt/location-tracker/db.sqlite3 root@VPS_IP:/opt/location-tracker/
   ssh root@VPS_IP 'chown locationtracker:locationtracker /opt/location-tracker/db.sqlite3'
   ```
4. Copy the APK update dir:
   ```bash
   sudo rsync -avz /opt/location-tracker/app_updates/ root@VPS_IP:/opt/location-tracker/app_updates/
   ssh root@VPS_IP 'chown -R locationtracker:locationtracker /opt/location-tracker/app_updates'
   ```
5. Flip the DuckDNS A record for `tracker.example.com` to the VPS IP.
   Existing APKs will start hitting the VPS within 20 min (their next poll).
6. Leave the home box running as a warm standby for 24-48 h, then shut it down.

## Subsequent deploys

```bash
./deploy/deploy.sh root@YOUR.VPS.IP
```
Same command. `.env`, `db.sqlite3`, `app_updates/`, and `certs/` are preserved.

## Backups (run from your local machine)

```bash
./deploy/backup.sh root@YOUR.VPS.IP
```
Downloads a SQLite dump to `backups/location-tracker-YYYYMMDD-HHMMSS.db`.
Prunes backups older than 30 days.

Cron example (nightly at 3am):
```
0 3 * * * ./deploy/backup.sh root@vps >> /var/log/location-tracker-backup.log 2>&1
```

## Periodic maintenance

The `UsedNonce` table grows forever — rows are inserted with a 10-minute
TTL but never deleted. Add a weekly cron on the VPS to prune:

```bash
# /etc/cron.weekly/location-tracker-prune
#!/bin/sh
sqlite3 /opt/location-tracker/db.sqlite3 \
  "DELETE FROM tracker_usednonce WHERE expires_at < datetime('now'); VACUUM;"
```

## APK updates (existing pipeline, unchanged)

```bash
cd ~/Scripts/location_server
./deploy-apk.sh
scp android_app/app/build/outputs/apk/release/app-release.apk \
    root@VPS_IP:/opt/location-tracker/app_updates/latest.apk
scp android_app/app/build/outputs/apk/release/version.json \
    root@VPS_IP:/opt/location-tracker/app_updates/version.json
```
Installed apps auto-detect the new version via `/api/app-version/` and
self-upgrade. Must use the same `release-keystore.jks` so GrapheneOS
accepts the update.

## Security posture

| Layer | Protection |
|---|---|
| Network | ufw firewall (22/80/443/8443), fail2ban for SSH (set up by CodeMill) |
| OS | unattended-upgrades, 2 GB swap (set up by CodeMill) |
| TLS | Caddy auto-HTTPS via Let's Encrypt for new vhost; self-signed for legacy |
| Django | `DEBUG=False`, HSTS, secure cookies, SSL redirect, CSRF scoping |
| Admin | Non-obvious URL, IP allowlist + basic auth (Caddy-level) |
| App | Runs as unprivileged `locationtracker` user, not root |
| Secrets | `.env` chmod 600, gitignored, auto-generated secret key |
| Client auth | HMAC-SHA256 per-device signing (transport is TLS, auth is HMAC) |

## Layout on the VPS

```
/opt/location-tracker/                  app directory (synced on each deploy)
/opt/location-tracker/.venv/             virtualenv (rebuilt on each deploy)
/opt/location-tracker/.env              env file (PRESERVED across deploys)
/opt/location-tracker/db.sqlite3        database (PRESERVED)
/opt/location-tracker/app_updates/      APK + version.json (PRESERVED)
/opt/location-tracker/certs/             self-signed cert + key (PRESERVED)
/opt/location-tracker/cache/             file-based cache
/opt/location-tracker/staticfiles/       collected static files
/etc/systemd/system/locationtracker.service
/etc/caddy/sites/location-tracker.caddy  (two vhosts: new + legacy)
/var/log/caddy/location-tracker.log
/var/log/caddy/location-tracker-legacy.log
```

## Operational commands (on the VPS)

```bash
systemctl status locationtracker          # app status
journalctl -u locationtracker -f          # app logs (live)
systemctl status caddy                    # proxy status
journalctl -u caddy -f                    # proxy logs (live)
tail -f /var/log/caddy/location-tracker.log
sudo -u locationtracker /opt/location-tracker/.venv/bin/python \
    /opt/location-tracker/manage.py createsuperuser
```

## Port allocation on the VPS

| Site | Gunicorn port | Caddy vhost |
|---|---|---|
| The Code Mill | 8765 | thecodemill.dev |
| J C Burns | 8766 | jcburns.org |
| Location Tracker (new) | 8767 | tracker.example.com |
| Location Tracker (legacy) | 8767 | tracker.example.com:8443 |

## Local development

```bash
cd ~/Scripts/location_server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python manage.py migrate
python manage.py runserver 127.0.0.1:8000
```
