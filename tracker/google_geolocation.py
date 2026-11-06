"""Google Geolocation API client.

Resolves a location from a set of WiFi access points using Google's
geolocation API. Used by the server to derive lat/lng from the WiFi scan
data sent by Android clients; the client's GPS fix is used as a fallback
when the API call fails or no WiFi data is available.

All sightings in a payload (WiFi, cell towers, BLE devices) are assigned
the same location — derived from the WiFi scan via Google's API, or GPS
as a fallback when no WiFi networks are present.
"""
import json
import os
import urllib.error
import urllib.request

GEOLOCATE_URL = "https://www.googleapis.com/geolocation/v1/geolocate"


class GeolocationError(Exception):
    """Raised when the Google API call cannot return a usable location."""


def _to_access_point(wifi):
    mac = (wifi.get("bssid") or "").strip().lower()
    if not mac:
        return None
    ap = {"macAddress": mac}
    if wifi.get("signal_dbm") is not None:
        ap["signalStrength"] = int(wifi["signal_dbm"])
    if wifi.get("frequency_mhz") is not None:
        ap["frequency"] = int(wifi["frequency_mhz"])
    if wifi.get("channel") is not None:
        ap["channel"] = int(wifi["channel"])
    return ap


def _load_key(settings):
    key = os.environ.get("GOOGLE_GEOLOCATION_API_KEY")
    if key:
        return key
    path = getattr(settings, "GOOGLE_GEOLOCATION_API_KEY_FILE", None)
    if not path:
        raise GeolocationError(
            "No Google API key configured (set env GOOGLE_GEOLOCATION_API_KEY "
            "or settings.GOOGLE_GEOLOCATION_API_KEY_FILE)"
        )
    try:
        with open(path) as f:
            key = f.read().strip()
    except OSError as e:
        raise GeolocationError(f"Cannot read Google API key file {path}: {e}")
    if not key:
        raise GeolocationError(f"Google API key file is empty: {path}")
    return key


def geolocate_wifi(wifi_networks, settings):
    """Call Google's geolocation API with a list of wifi network dicts.

    Returns (latitude, longitude, accuracy_m) or raises GeolocationError.
    """
    aps = [ap for ap in (_to_access_point(w) for w in wifi_networks) if ap]
    if not aps:
        raise GeolocationError("No usable WiFi access points (need a bssid)")

    key = _load_key(settings)
    body = json.dumps({"wifiAccessPoints": aps}).encode()
    url = f"{GEOLOCATE_URL}?key={key}"
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise GeolocationError(f"Google API HTTP {e.code}: {e.read().decode()[:200]}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise GeolocationError(f"Network error calling Google: {e}")

    loc = data.get("location") or {}
    lat, lng = loc.get("lat"), loc.get("lng")
    if lat is None or lng is None:
        raise GeolocationError(f"No location in Google response: {data}")
    accuracy = data.get("accuracy")
    return float(lat), float(lng), float(accuracy) if accuracy is not None else None
