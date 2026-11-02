# GrapheneOS Location Tracker

A self-hosted device-location / wardriving platform: a Django REST API +
Kotlin/Compose Android client + NixOS systemd module, deployed on a small
NixOS VPS behind Caddy. Devices (the user's phone + a long-running Android
client) periodically POST WiFi scan + cell-tower + BLE sightings to the
server, which resolves location via the Google Geolocation API (with a GPS
fallback), records everything to SQLite, and renders a live map + per-device
route history. Includes a CVE-vendor lookup so each device's manufacturer can
be cross-checked against the local CVE-search database.

## Features

### Django backend (`tracker/` app)
- **Receive-location API** — accepts a HMAC-signed location + WiFi scan +
  cell + BLE payload per device, resolves lat/lng via Google Geolocation,
  falls back to the device's GPS fix when no APs match, and records:
  - `LocationUpdate` (lat/lng/accuracy/source/timestamp)
  - `WifiSighting` (per-AP BSSID + SSID + signal + channel)
  - `CellTowerSighting`
  - `NearbyDeviceSighting` (BLE — Apple Continuity + Google Fast Pair)
  - `DeviceLocationHistory` (per-device polyline)
- **HMAC authentication** (`middleware.py`) — every request signed
  over `device_id\ntimestamp\nnonce\nsha256(body)`, 5-min clock skew,
  nonce-replay protection via a `UsedNonce` table.
- **Map + device-map views** — Leaflet map rendering recent points +
  per-device route polyline + OUI-resolved MAC manufacturers.
- **Admin APIs** — admin login / create-device / client-locations /
  nearby-devices / device-history.
- **Pending-request API** — server can poke a device for an on-demand
  fix (`PendingRequest` resolves when the device next polls).
- **App update pipeline** — `app_latest_version` + `app_download_apk`
  endpoints serve versioned APK updates to the Android client (the app
  self-checks on boot).
- **CVE lookup** — proxies to a local `cve-search` Docker container to
  highlight CVEs for the device's manufacturer.
- **App log ingestion** — the client uploads app logs for debugging.

### Android client (`android_app/`, Kotlin + Compose)
- `LocationForegroundService` — 10-min tracking loop + one-shot on-demand
  requests + privacy-mode honour + offline payload store + flush on next
  poll.
- `LocationCollector` — GPS fix (30s timeout) + WiFi scan (15s, rejects
  stale `scanResults`).
- `ApiClient` — OkHttp with **embedded raw-cert trust store + hostname
  pinning + certificate pinning** + HMAC-signed requests + cookie jar.
- `SecurityManager` — HMAC-SHA256 request signing + SecureRandom nonce.
- `BootReceiver` — auto-start the foreground service on device boot.
- `UpdateChecker` — pulls APK updates from the server.
- Screens — `MainScreen`, `SettingsScreen`, `AdminScreen` (with map +
  device management).
- `NearbyDeviceCollector` + `CellTowerCollector` — feed into the BLE /
  cell-tower sightings sent to the server.

### Ops
- `nixos/location-tracker.nix` — NixOS systemd module creating a
  `locationtracker` system user running gunicorn on 127.0.0.1:8000 behind
  Caddy (`tracker.example.com:443` self-signed cert demo config; replace
  with Let's Encrypt in production). ExecStartPre runs migrate +
  collectstatic.
- `deploy/install.sh` (~400 lines) — full remote installer: systemd unit,
  Caddyfile generation with admin-IP allowlist + basic-auth + Let's
  Encrypt + legacy self-signed vhost, venv from local wheels, self-signed
  cert handling.
- `deploy/deploy.sh` — rsync push + remote install (reads `DEPLOY_USER`/
  `DEPLOY_HOST` from `.env`).
- `deploy/backup.sh` — SSH-based SQLite `.backup` pull with 30-day
  pruning (cron-ready).

## Tech stack

- Django 5 + gunicorn behind Caddy
- SQLite (wards the dataset; MariaDB or Postgres would also work)
- okHttp + Kotlin/Compose + Room on the Android side
- HMAC-SHA256 + nonce replay protection (no session tokens, no API keys
  in the URL) — every request authenticated per the scheme above
- Google Geolocation API (resolves WiFi scan → lat/lng) — key read from
  a file (`~/.config/google_api_key` on deploy), never in source
- BLE manufacturer-data parser (`ble_parser.py`) — decodes Apple
  Continuity (AirPods / AirTag / Watch type codes) and Google Fast Pair
  (Pixel Buds / Galaxy Buds / etc.)
- `load_oui` management command — downloads IEEE OUI registry for
  MAC→vendor
- CVE-search (sibling Docker container) for manufacturer CVE highlights

## Layout

```
location_server/             # Django project (settings, urls, wsgi, asgi)
tracker/                     # main app
  models.py                  # Device, LocationUpdate, WifiSighting,
                             # CellTowerSighting, NearbyDeviceSighting,
                             # OuiEntry, PendingRequest, AppLog, etc.
  views.py                   # receive_location + admin + map + CVE lookup +
                             # APK update + route polylines
  middleware.py              # HMAC + nonce replay protection
  google_geolocation.py
  ble_parser.py              # Apple Continuity + Google Fast Pair decoder
  admin.py + apps.py + urls.py
  management/commands/load_oui.py
nixos/location-tracker.nix
deploy/
  install.sh                 # ~400-line idempotent remote installer
  deploy.sh                  # rsync push
  backup.sh                  # SQLite .backup pull (30-day)
  MIGRATION-TODO.md          # home-to-VPS migration checklist
  .env.example               # full env-var reference
android_app/                 # Kotlin / Compose client
app/build.gradle.kts         # (release is unsigned in this portfolio copy;
                             #  configure your own keystore via env vars)
```

## Set up

### Server (NixOS)

1. Set DNS for `tracker.example.com` → your VPS IP.
2. Run `deploy/install.sh root@YOUR.VPS.IP` from a local clone (reads the
   sibling `deploy/.env`).
3. Or use the NixOS module: copy `nixos/location-tracker.nix` to
   `/etc/nixos/`, add it to `configuration.nix` imports, `nixos-rebuild
   switch`, enable + start the service.

### Android client

Open the `android_app/` folder in Android Studio, plug in a device, run.
The app's default server is `tracker.example.com` (override in Settings).
On first launch, scan the registration QR it serves at `/map/` to enrol
the device.

## License

MIT — see [LICENSE](LICENSE).
