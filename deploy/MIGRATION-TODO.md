# Location Tracker — migration TODO

Pending steps to complete the home → VPS migration. Work through in order.

1. **Add A record in Njalla** for `tracker.example.com` → YOUR.VPS.IP
2. **Edit `/opt/location-tracker/.env` on the VPS**:
   - `ADMIN_BASIC_AUTH_PASS` — a strong password (replace placeholder)
   - `ADMIN_ALLOWED_IPS` — your home IP (find at https://ifconfig.me)
   - `DJANGO_ADMIN_URL` — a secret path, not the default `staff-control`
3. **Copy the self-signed cert** from the home NixOS box to the VPS:
   ```bash
   sudo rsync -avz /etc/caddy/certs/cert.pem user@YOUR.VPS.IP:/opt/location-tracker/certs/cert.pem
   sudo rsync -avz /etc/caddy/certs/key.pem  user@YOUR.VPS.IP:/opt/location-tracker/certs/key.pem
   ```
4. **Re-run the deploy** to activate the legacy vhost:
   ```bash
   cd ~/Scripts/location_server
   ./deploy/deploy.sh user@YOUR.VPS.IP
   ```
5. **Migrate the live DB** (brief, invisible downtime):
   ```bash
   sudo systemctl stop location-tracker   # on home box
   sudo rsync -avz /opt/location-tracker/db.sqlite3 user@YOUR.VPS.IP:/opt/location-tracker/
   ssh user@YOUR.VPS.IP 'sudo chown locationtracker:locationtracker /opt/location-tracker/db.sqlite3'
   ```
   Also copy the APK update dir:
   ```bash
   sudo rsync -avz /opt/location-tracker/app_updates/ user@YOUR.VPS.IP:/opt/location-tracker/app_updates/
   ssh user@YOUR.VPS.IP 'sudo chown -R locationtracker:locationtracker /opt/location-tracker/app_updates'
   ```
6. **Set up the DB mirror cron on the home box** (every minute, until cutover):
   ```bash
   # /etc/cron.d/location-tracker-mirror (run as root)
   * * * * * root sqlite3 /opt/location-tracker/db.sqlite3 '.backup /tmp/lt-mirror.db' && rsync -az /tmp/lt-mirror.db user@YOUR.VPS.IP:/opt/location-tracker/db.sqlite3
   ```
7. **When ready to cut over**:
   - Stop the home service: `sudo systemctl stop location-tracker`
   - Run the mirror one final time
   - Flip the DuckDNS A record for `tracker.example.com` to YOUR.VPS.IP
   - Leave the home box running as warm standby for 24-48h, then shut it down

## Verification (run anytime after step 4)
```bash
ssh user@YOUR.VPS.IP '
  curl -sk -o /dev/null -w "static: HTTP %{http_code}\n" https://tracker.example.com/static/admin/css/base.css --resolve tracker.example.com:443:127.0.0.1
  curl -sk -o /dev/null -w "map: HTTP %{http_code}\n"    https://tracker.example.com/map/ --resolve tracker.example.com:443:127.0.0.1
  curl -sk -o /dev/null -w "api: HTTP %{http_code}\n"    https://tracker.example.com/api/location/ --resolve tracker.example.com:443:127.0.0.1
'
# Expected: 200, 200, 401
```

## After cutover — periodic maintenance
Weekly cron on the VPS to prune the UsedNonce table:
```bash
# /etc/cron.weekly/location-tracker-prune
sqlite3 /opt/location-tracker/db.sqlite3 "DELETE FROM tracker_usednonce WHERE expires_at < datetime('now'); VACUUM;"
```

## Future — port clients to the new domain
Bump `versionCode` in `android_app/app/build.gradle.kts`, change the defaults in
`AppSettingsStore.kt` (`serverHost=tracker.example.com`, `serverPort=443`),
drop the embedded `R.raw.server_cert` trust in `ApiClient.kt` (use system trust
for Let's Encrypt), build a new APK with `./deploy-apk.sh`, and push it to
`/opt/location-tracker/app_updates/`. Installed apps auto-upgrade via
`/api/app-version/`.
