#!/usr/bin/env python3
"""Standalone test for the Google Geolocation API against WiFi scan data.

Verifies the pipeline we intend to wire into the Django server: given a set of
WiFi networks (as collected by the Android app), call Google's geolocate
endpoint and print the resulting lat/lng/accuracy, optionally comparing it to
a known GPS fix.

No third-party deps — uses urllib so it runs under any python3.

Usage:
  # Use the built-in realistic sample scan:
  python3 test_geolocate.py

  # Feed a real scan from a JSON file (format matches the Android payload):
  python3 test_geolocate.py scan.json

  # Feed a scan on stdin and compare to a known GPS fix:
  cat scan.json | python3 test_geolocate.py --gps 52.5200,13.4050

  # Override key file location:
  python3 test_geolocate.py --key-file /path/to/key

JSON scan format (one or the other):
  {"wifi_networks": [ {"ssid","bssid","signal_dbm","frequency_mhz","channel","is_connected"}, ... ]}
  -- or a bare list: [ {"bssid","signal_dbm","frequency_mhz",...}, ... ]
"""
import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.request

GEOLOCATE_URL = "https://www.googleapis.com/geolocation/v1/geolocate"

SAMPLE_SCAN = [
    {"ssid": "HomeNetwork", "bssid": "aa:bb:cc:dd:ee:ff", "signal_dbm": -45,
     "frequency_mhz": 2412, "channel": 1, "is_connected": True},
    {"ssid": "NeighborWiFi", "bssid": "11:22:33:44:55:66", "signal_dbm": -78,
     "frequency_mhz": 5180, "channel": 36, "is_connected": False},
    {"ssid": "CoffeeShop", "bssid": "de:ad:be:ef:00:01", "signal_dbm": -62,
     "frequency_mhz": 2437, "channel": 6, "is_connected": False},
]


def load_key(path):
    try:
        with open(os.path.expanduser(path)) as f:
            key = f.read().strip()
    except FileNotFoundError:
        sys.exit(f"ERROR: API key file not found: {path}\n"
                f"       Create it with:  echo 'YOUR_KEY' > {path} && chmod 600 {path}")
    if not key:
        sys.exit(f"ERROR: API key file is empty: {path}")
    return key


def to_google_ap(wifi):
    """Map our WifiNetwork payload fields -> Google's wifiAccessPoint schema."""
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


def geolocate(key, wifi_access_points):
    body = json.dumps({"wifiAccessPoints": wifi_access_points}).encode()
    url = f"{GEOLOCATE_URL}?key={key}"
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        err_body = e.read().decode()
        try:
            err_json = json.loads(err_body)
            msg = err_json.get("error", {}).get("message", err_body)
        except json.JSONDecodeError:
            msg = err_body
        sys.exit(f"Google API error (HTTP {e.code}): {msg}")
    except urllib.error.URLError as e:
        sys.exit(f"Network error calling Google: {e}")


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = (math.sin(dphi / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2)
    return 2 * R * math.asin(math.sqrt(a))


def load_scan(arg_path):
    if arg_path:
        with open(arg_path) as f:
            raw = json.load(f)
    else:
        raw = None
        if not sys.stdin.isatty():
            try:
                raw = json.load(sys.stdin)
            except (json.JSONDecodeError, EOFError, ValueError):
                raw = None
        if raw is None:
            print("(no scan on stdin/file; using built-in SAMPLE_SCAN)\n")
            return SAMPLE_SCAN

    if isinstance(raw, dict) and "wifi_networks" in raw:
        return raw["wifi_networks"]
    if isinstance(raw, list):
        return raw
    sys.exit("ERROR: scan JSON must be a list of APs or "
             "{'wifi_networks': [...]}")


def main():
    p = argparse.ArgumentParser(description="Test Google Geolocation API with WiFi data.")
    p.add_argument("scan_file", nargs="?",
                   help="JSON file with wifi_networks (omit to use stdin or sample)")
    p.add_argument("--key-file", default=os.path.expanduser("~/.config/google_api_key"),
                   help="File containing the Google API key (default: ~/.config/google_api_key)")
    p.add_argument("--gps", metavar="LAT,LNG",
                   help="Known GPS fix to compare against (prints distance in metres)")
    args = p.parse_args()

    key = load_key(args.key_file)
    wifi = load_scan(args.scan_file)

    aps = [a for a in (to_google_ap(w) for w in wifi) if a is not None]
    if not aps:
        sys.exit("ERROR: no usable APs in scan (need at least one with a bssid)")

    print(f"Sending {len(aps)} access point(s) to Google:\n")
    for a in aps:
        print(f"  {a['macAddress']:20s}  "
              f"sig={a.get('signalStrength','-'):>4}  "
              f"freq={a.get('frequency','-'):>5}  ch={a.get('channel','-')}")
    print()

    result = geolocate(key, aps)
    lat = result.get("location", {}).get("lat")
    lng = result.get("location", {}).get("lng")
    acc = result.get("accuracy")

    if lat is None or lng is None:
        sys.exit(f"ERROR: no location in response: {json.dumps(result)}")

    print(f"Google geolocation result:")
    print(f"  latitude  : {lat}")
    print(f"  longitude : {lng}")
    print(f"  accuracy  : {acc} m  (radius of 68% confidence)")

    if args.gps:
        try:
            glat, glng = (float(x) for x in args.gps.split(","))
        except ValueError:
            sys.exit(f"ERROR: --gps must be LAT,LNG (got '{args.gps}')")
        d = haversine_m(lat, lng, glat, glng)
        print(f"\nKnown GPS fix: {glat}, {glng}")
        print(f"Distance from Google result: {d:.1f} m"
              f"  ({'within' if d <= (acc or 0) else 'OUTSIDE'} Google's stated accuracy radius)")


if __name__ == "__main__":
    main()
