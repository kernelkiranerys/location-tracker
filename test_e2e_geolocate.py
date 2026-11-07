"""End-to-end test: POST a signed location update with WiFi data to the
running server config and verify the stored LocationUpdate uses Google's
geolocation as primary, GPS as fallback.

Run:
  PYTHONPATH=. \
  DJANGO_SETTINGS_MODULE=location_server.settings \
  /nix/store/ra7vj75krpydfjkrb8i65j4dxghr2pmc-python3-3.12.13-env/bin/python \
  ./test_e2e_geolocate.py
"""
import hashlib
import hmac
import json
import os
import time

import django
from django.conf import settings
from django.test import Client

django.setup()

from tracker.models import Device, LocationUpdate  # noqa: E402

# --- create a test device with a known secret key ---
SECRET = "test-secret-key-32bytes-long-aaaaaaaaa"
dev, _ = Device.objects.get_or_create(
    device_id="00000000-0000-0000-0000-000000000001",
    defaults={"name": "E2E Test Device", "secret_key": SECRET},
)

# --- build a signed request body with the real WiFi scan ---
with open("/tmp/real_scan.json") as f:
    wifi_networks = json.load(f)

body = json.dumps({
    "latitude": 53.819247414763396,
    "longitude": -1.5377915160866131,
    "accuracy": 2.3,
    "altitude": 34.0,
    "timestamp": int(time.time() * 1000),
    "triggered_by": "scheduled",
    "wifi_networks": wifi_networks,
}).encode()

device_id = str(dev.device_id)
timestamp = str(int(time.time() * 1000))
nonce = "abc123def456"
body_hash = hashlib.sha256(body).hexdigest()
signing_string = f"{device_id}\n{timestamp}\n{nonce}\n{body_hash}"
signature = hmac.new(SECRET.encode(), signing_string.encode(), hashlib.sha256).hexdigest()

client = Client()
resp = client.post(
    "/api/location/",
    data=body,
    content_type="application/json",
    HTTP_HOST="localhost",
    HTTP_X_DEVICE_ID=device_id,
    HTTP_X_TIMESTAMP=timestamp,
    HTTP_X_NONCE=nonce,
    HTTP_X_SIGNATURE=signature,
)
print(f"POST /api/location/ -> {resp.status_code}")
print(f"response: {resp.content[:300]}")
assert resp.status_code == 200, resp.content

loc = LocationUpdate.objects.filter(device=dev).first()
print(f"\nStored LocationUpdate id={loc.id}:")
print(f"  latitude        : {loc.latitude}")
print(f"  longitude       : {loc.longitude}")
print(f"  accuracy        : {loc.accuracy}")
print(f"  location_source : {loc.location_source}")
print(f"  gps_latitude    : {loc.gps_latitude}")
print(f"  gps_longitude   : {loc.gps_longitude}")
print(f"  gps_accuracy    : {loc.gps_accuracy}")

if loc.location_source == "google_wifi":
    print("\nPASS: server used Google WiFi geolocation as primary.")
else:
    print("\nNOTE: server fell back to GPS. Check that the Google API key is")
    print("      readable at:", settings.GOOGLE_GEOLOCATION_API_KEY_FILE)
    print("      or set env GOOGLE_GEOLOCATION_API_KEY.")

# cleanup
loc.delete()
dev.delete()
