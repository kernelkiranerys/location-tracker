import json
import logging
import math
import os
import secrets
import base64
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone as dt_timezone

from django.conf import settings
from django.http import FileResponse, Http404
from django.contrib.admin.views.decorators import staff_member_required
from django.contrib.auth import authenticate, login
from django.db import transaction
from django.http import JsonResponse, HttpResponseBadRequest
from django.shortcuts import render
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from .google_geolocation import GeolocationError, geolocate_wifi
from .ble_parser import parse_ble_device_info
from .models import (
    CellTowerSighting,
    Device,
    DeviceLocationHistory,
    LocationUpdate,
    NearbyDeviceSighting,
    OuiEntry,
    PendingRequest,
    WifiNetwork,
    WifiSighting,
    AppLog,
)

logger = logging.getLogger("tracker")


def _qr_payload(device):
    return json.dumps({
        "host": settings.TRACKER_SERVER_HOST,
        "port": settings.TRACKER_SERVER_PORT,
        "cert_pin": settings.TRACKER_CERT_PIN_HASH,
        "device_id": str(device.device_id),
        "secret_key": device.secret_key,
    })


def _lookup_manufacturer(mac_address):
    """Look up the manufacturer for a MAC address using the OUI database."""
    if not mac_address or len(mac_address) < 8:
        return None
    # Normalize: extract first 3 octets as XX:XX:XX
    clean = mac_address.replace("-", ":").upper()
    parts = clean.split(":")
    if len(parts) < 3:
        return None
    prefix = f"{parts[0]}:{parts[1]}:{parts[2]}"
    try:
        entry = OuiEntry.objects.get(prefix=prefix)
        return entry.organization
    except OuiEntry.DoesNotExist:
        return None


def _record_history(device_type, identifier, lat, lng, now):
    """Record or update a location history entry for a wardriving device."""
    # Round to ~1m precision to avoid near-duplicate entries
    rlat = round(lat, 5)
    rlng = round(lng, 5)
    obj, created = DeviceLocationHistory.objects.select_for_update().get_or_create(
        device_identifier=identifier,
        latitude=rlat,
        longitude=rlng,
        defaults={
            "device_type": device_type,
            "last_seen": now,
        },
    )
    if not created:
        obj.last_seen = now
        obj.sighting_count += 1
        obj.save()


@csrf_exempt
@require_http_methods(["GET", "POST"])
def register_device(request):
    created_device = None
    if request.method == "POST":
        name = (request.POST.get("name") or "").strip()
        if name:
            secret_key = base64.b64encode(secrets.token_bytes(32)).decode()
            created_device = Device.objects.create(name=name, secret_key=secret_key)
    return render(request, "register.html", {
        "created_device": created_device,
        "qr_payload": _qr_payload(created_device) if created_device else None,
        "qr_host": settings.TRACKER_SERVER_HOST,
        "qr_port": settings.TRACKER_SERVER_PORT,
    })


@require_http_methods(["GET"])
def device_qr(request, device_id):
    try:
        device = Device.objects.get(device_id=device_id)
    except Device.DoesNotExist:
        return JsonResponse({"error": "Unknown device"}, status=404)
    return render(request, "register.html", {
        "created_device": device,
        "qr_payload": _qr_payload(device),
        "qr_host": settings.TRACKER_SERVER_HOST,
        "qr_port": settings.TRACKER_SERVER_PORT,
    })


@csrf_exempt
@require_http_methods(["POST"])
def receive_location(request):
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    required = ["timestamp"]
    for field in required:
        if field not in data:
            return JsonResponse({"error": f"Missing {field}"}, status=400)

    ts_str = data["timestamp"]
    try:
        if isinstance(ts_str, (int, float)):
            ts = datetime.fromtimestamp(ts_str / 1000, tz=dt_timezone.utc)
        else:
            ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return JsonResponse({"error": "Invalid timestamp format"}, status=400)

    triggered_by = data.get("triggered_by", "scheduled")
    wifi_networks = data.get("wifi_networks", [])
    cell_towers = data.get("cell_towers", [])
    nearby_devices = data.get("nearby_devices", [])
    operator_info = data.get("operator", {})

    gps_lat = data.get("latitude")
    gps_lng = data.get("longitude")
    gps_acc = data.get("accuracy")

    # --- Location resolution ---
    # WiFi geolocation is primary — used for ALL sightings (WiFi, cell, BLE).
    # GPS is only a fallback when no WiFi networks are in the payload.
    lat, lng, acc, source = gps_lat, gps_lng, gps_acc, "gps"
    if wifi_networks:
        try:
            g_lat, g_lng, g_acc = geolocate_wifi(wifi_networks, settings)
            lat, lng, acc, source = g_lat, g_lng, g_acc, "google_wifi"
            logger.info("WiFi geolocation: %.6f, %.6f (acc %sm)", lat, lng, acc)
            if gps_lat is not None and gps_lng is not None:
                disagreement_m = _haversine(g_lat, g_lng, gps_lat, gps_lng)
                threshold_m = getattr(settings, "TRACKER_WIFI_GPS_DISAGREEMENT_M", 1000)
                max_gps_acc_m = getattr(settings, "TRACKER_WIFI_GPS_MAX_ACC_M", 200)
                if disagreement_m > threshold_m and (gps_acc is None or gps_acc <= max_gps_acc_m):
                    logger.warning(
                        "WiFi/GPS disagreement: wifi=%.6f,%.6f (acc %sm) vs gps=%.6f,%.6f (acc %sm) "
                        "-> %.0fm apart (>%sm threshold); preferring GPS (likely stale cached WiFi scanResults)",
                        g_lat, g_lng, g_acc, gps_lat, gps_lng, gps_acc,
                        disagreement_m, threshold_m,
                    )
                    lat, lng, acc, source = gps_lat, gps_lng, gps_acc, "gps"
                elif disagreement_m > threshold_m:
                    logger.warning(
                        "WiFi/GPS disagreement: wifi=%.6f,%.6f vs gps=%.6f,%.6f -> %.0fm apart "
                        "(>%sm threshold), but GPS accuracy %sm exceeds %sm cap; keeping WiFi result",
                        g_lat, g_lng, gps_lat, gps_lng,
                        disagreement_m, threshold_m, gps_acc, max_gps_acc_m,
                    )
        except GeolocationError as e:
            logger.warning("WiFi geolocation failed, using GPS fallback: %s", e)

    now = timezone.now()

    logger.info(
        "LOCATION received device=%s triggered_by=%s source=%s "
        "lat=%.5f lng=%.5f acc=%s wifi_aps=%d",
        request.device.name,
        triggered_by,
        source,
        lat,
        lng,
        acc,
        len(wifi_networks),
    )

    with transaction.atomic():
        loc = LocationUpdate.objects.create(
            device=request.device,
            latitude=lat,
            longitude=lng,
            accuracy=acc,
            altitude=data.get("altitude"),
            speed=data.get("speed"),
            bearing=data.get("bearing"),
            timestamp=ts,
            triggered_by=triggered_by,
            location_source=source,
            gps_latitude=gps_lat,
            gps_longitude=gps_lng,
            gps_accuracy=gps_acc,
            sequence=data.get("sequence", 0),
        )

        # Store per-update WiFi network records (original functionality)
        for wifi in wifi_networks:
            WifiNetwork.objects.create(
                location_update=loc,
                ssid=wifi.get("ssid", ""),
                bssid=wifi.get("bssid", ""),
                signal_dbm=wifi.get("signal_dbm"),
                frequency_mhz=wifi.get("frequency_mhz"),
                is_connected=wifi.get("is_connected", False),
                channel=wifi.get("channel"),
                capabilities=wifi.get("capabilities", ""),
                encryption=wifi.get("encryption", "UNKNOWN"),
                frequency_band=wifi.get("frequency_band", ""),
            )

        # --- War-driving: update aggregated sightings ---

        # WiFi sightings — use WiFi geolocation (or GPS fallback)
        for wifi in wifi_networks:
            bssid = wifi.get("bssid", "").upper()
            if not bssid:
                continue
            sighting, created = WifiSighting.objects.select_for_update().get_or_create(
                bssid=bssid,
                defaults={
                    "ssid": wifi.get("ssid", ""),
                    "encryption": wifi.get("encryption", "UNKNOWN"),
                    "capabilities": wifi.get("capabilities", ""),
                    "frequency_band": wifi.get("frequency_band", ""),
                    "signal_dbm": wifi.get("signal_dbm"),
                    "latitude": lat,
                    "longitude": lng,
                    "last_seen": now,
                    "last_device": request.device,
                },
            )
            if not created:
                sighting.ssid = wifi.get("ssid", "") or sighting.ssid
                sighting.signal_dbm = wifi.get("signal_dbm") or sighting.signal_dbm
                sighting.encryption = wifi.get("encryption", sighting.encryption)
                sighting.capabilities = wifi.get("capabilities", sighting.capabilities)
                sighting.frequency_band = wifi.get("frequency_band", sighting.frequency_band)
                if lat is not None:
                    sighting.latitude = lat
                if lng is not None:
                    sighting.longitude = lng
                sighting.last_seen = now
                sighting.sighting_count += 1
                sighting.last_device = request.device
                sighting.save()

        # Cell tower sightings
        # Use tower-broadcast coordinates if present, otherwise WiFi-derived location
        for cell in cell_towers:
            radio = cell.get("radio_technology", "UNKNOWN")
            mcc = cell.get("mcc", "")
            mnc = cell.get("mnc", "")
            cell_id = cell.get("cell_id")
            tower_lat = cell.get("tower_latitude")
            tower_lng = cell.get("tower_longitude")
            # Build a lookup key
            lookup = {"radio_technology": radio, "mcc": mcc, "mnc": mnc}
            if cell_id is not None and cell_id != 2147483647:
                lookup["cell_id"] = cell_id
            else:
                lookup["pci"] = cell.get("pci")

            sighting, created = CellTowerSighting.objects.select_for_update().get_or_create(
                **lookup,
                defaults={
                    "lac": cell.get("lac"),
                    "tac": cell.get("tac"),
                    "pci": cell.get("pci"),
                    "signal_dbm": cell.get("signal_strength"),
                    "rsrp": cell.get("rsrp"),
                    "rsrq": cell.get("rsrq"),
                    "snr": cell.get("snr"),
                    "latitude": tower_lat if tower_lat is not None else lat,
                    "longitude": tower_lng if tower_lng is not None else lng,
                    "last_seen": now,
                    "last_device": request.device,
                },
            )
            if not created:
                sighting.signal_dbm = cell.get("signal_strength") or sighting.signal_dbm
                sighting.rsrp = cell.get("rsrp") or sighting.rsrp
                sighting.rsrq = cell.get("rsrq") or sighting.rsrq
                sighting.snr = cell.get("snr") or sighting.snr
                # Prefer tower-broadcast coordinates, fall back to payload location
                if tower_lat is not None:
                    sighting.latitude = tower_lat
                elif lat is not None:
                    sighting.latitude = lat
                if tower_lng is not None:
                    sighting.longitude = tower_lng
                elif lng is not None:
                    sighting.longitude = lng
                sighting.last_seen = now
                sighting.sighting_count += 1
                sighting.last_device = request.device
                sighting.save()

        # Nearby BLE device sightings
        for dev in nearby_devices:
            address = dev.get("address", "").upper()
            if not address:
                continue
            sighting, created = NearbyDeviceSighting.objects.select_for_update().get_or_create(
                address=address,
                defaults={
                    "name": dev.get("name", "") or "",
                    "device_type": dev.get("device_type", "BLE"),
                    "rssi": dev.get("rssi"),
                    "tx_power": dev.get("tx_power"),
                    "manufacturer_data": json.dumps(dev.get("manufacturer_data", [])),
                    "service_uuids": json.dumps(dev.get("service_uuids", [])),
                    "latitude": lat,
                    "longitude": lng,
                    "last_seen": now,
                    "last_device": request.device,
                },
            )
            if not created:
                sighting.name = dev.get("name", "") or sighting.name
                sighting.rssi = dev.get("rssi") or sighting.rssi
                if dev.get("tx_power") is not None:
                    sighting.tx_power = dev.get("tx_power")
                if lat is not None:
                    sighting.latitude = lat
                if lng is not None:
                    sighting.longitude = lng
                sighting.last_seen = now
                sighting.sighting_count += 1
                sighting.last_device = request.device
                sighting.save()

        # --- Record location history for all devices in this payload ---
        if lat is not None and lng is not None:
            for wifi in wifi_networks:
                bssid = (wifi.get("bssid") or "").upper()
                if bssid:
                    _record_history("wifi", bssid, lat, lng, now)
            for cell in cell_towers:
                cell_id = cell.get("cell_id")
                radio = cell.get("radio_technology", "UNKNOWN")
                identifier = f'{radio}:{cell.get("mcc", "")}:{cell.get("mnc", "")}:{cell_id}' if cell_id else f'{radio}:{cell.get("pci", "")}'
                _record_history("cell", identifier, lat, lng, now)
            for dev in nearby_devices:
                address = (dev.get("address") or "").upper()
                if address:
                    _record_history("bluetooth", address, lat, lng, now)

    # Fulfill pending on-demand requests
    if triggered_by in ("on_demand", "privacy_override"):
        PendingRequest.objects.filter(
            device=request.device,
            fulfilled=False,
        ).update(fulfilled=True, fulfilled_at=now)

    return JsonResponse({"status": "ok", "location_id": loc.id})


@csrf_exempt
@require_http_methods(["POST"])
def privacy_request(request):
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    hours = data.get("hours")
    if hours not in [1, 2, 4, 8]:
        return JsonResponse({"error": "Invalid hours value"}, status=400)

    request.device.privacy_until = timezone.now() + timedelta(hours=hours)
    request.device.save(update_fields=["privacy_until"])
    return JsonResponse({"status": "ok", "privacy_until": request.device.privacy_until.isoformat()})


@csrf_exempt
@require_http_methods(["GET"])
def pending_request(request):
    has_pending = PendingRequest.objects.filter(
        device=request.device,
        fulfilled=False,
    ).exists()
    logger.info(
        "POLL device=%s pending=%s last_seen=%s",
        request.device.name,
        has_pending,
        request.device.last_seen.isoformat() if request.device.last_seen else "never",
    )
    return JsonResponse({"request_location": has_pending})


@staff_member_required
@require_http_methods(["POST"])
def trigger_location_request(request, device_id):
    try:
        device = Device.objects.get(device_id=device_id)
    except Device.DoesNotExist:
        return JsonResponse({"error": "Unknown device"}, status=404)
    PendingRequest.objects.create(device=device)
    pending_count = PendingRequest.objects.filter(device=device, fulfilled=False).count()
    logger.info(
        "TRIGGER queued device=%s device_id=%s total_pending=%d",
        device.name,
        str(device.device_id),
        pending_count,
    )
    return JsonResponse({"status": "queued", "device_id": str(device.device_id)})


# --- Original device tracking map ---


def map_view(request):
    devices = Device.objects.all()
    latest_locations = []
    for device in devices:
        latest = device.locations.order_by("-created_at", "-id").first()
        if latest:
            latest_request = device.pending_requests.order_by("-created_at", "-id").first()
            request_pending = device.pending_requests.filter(fulfilled=False).exists()
            latest_locations.append({
                "device_id": str(device.device_id),
                "name": device.name,
                "latitude": latest.latitude,
                "longitude": latest.longitude,
                "accuracy": latest.accuracy,
                "timestamp": latest.timestamp.isoformat(),
                "received_at": latest.created_at.isoformat(),
                "triggered_by": latest.triggered_by,
                "location_source": latest.location_source,
                "privacy_until": device.privacy_until.isoformat() if device.privacy_until else None,
                "request_time": latest_request.created_at.isoformat() if latest_request else None,
                "request_pending": request_pending,
            })
    # All registered devices for the route search dropdown
    client_devices = [{"device_id": str(d.device_id), "name": d.name} for d in devices]
    context = {
        "locations_json": json.dumps(latest_locations),
        "client_devices_json": json.dumps(client_devices),
    }
    return render(request, "map.html", context)


# --- War-driving device map ---


def _age_category(last_seen):
    """Return 'fresh', 'stale', or 'old' based on age."""
    if not last_seen:
        return "old"
    age = timezone.now() - last_seen
    if age < timedelta(weeks=1):
        return "fresh"
    elif age < timedelta(weeks=4):
        return "stale"
    return "old"


def _device_history(identifier, ref_lat, ref_lng, exclude_within_m=50.0):
    """Return previous locations for a device, excluding those within
    exclude_within_m of the reference location."""
    histories = DeviceLocationHistory.objects.filter(
        device_identifier=identifier,
    ).exclude(latitude=ref_lat, longitude=ref_lng).order_by("-last_seen")

    locations = []
    for h in histories:
        if ref_lat is not None and ref_lng is not None:
            dist = _haversine(h.latitude, h.longitude, ref_lat, ref_lng)
            if dist < exclude_within_m:
                continue
        locations.append({
            "lat": h.latitude,
            "lng": h.longitude,
            "first_seen": h.first_seen.isoformat(),
            "last_seen": h.last_seen.isoformat(),
            "sighting_count": h.sighting_count,
        })
    return locations


def device_map_view(request):
    """Renders the war-driving device map with all historically seen devices."""
    wifi_sightings = WifiSighting.objects.filter(latitude__isnull=False).values(
        "id", "bssid", "ssid", "encryption", "capabilities", "frequency_band",
        "signal_dbm", "latitude", "longitude", "first_seen", "last_seen",
        "sighting_count",
    )
    cell_sightings = CellTowerSighting.objects.filter(latitude__isnull=False).values(
        "id", "radio_technology", "mcc", "mnc", "cell_id", "lac", "tac", "pci",
        "signal_dbm", "rsrp", "rsrq", "snr", "latitude", "longitude",
        "first_seen", "last_seen", "sighting_count",
    )
    nearby_sightings = NearbyDeviceSighting.objects.filter(latitude__isnull=False).values(
        "id", "address", "name", "device_type", "rssi", "tx_power",
        "manufacturer_data", "service_uuids",
        "latitude", "longitude", "first_seen", "last_seen", "sighting_count",
    )

    devices_data = []
    for ws in wifi_sightings:
        manufacturer = _lookup_manufacturer(ws["bssid"])
        identifier = ws["bssid"].upper()
        prev_locs = _device_history(identifier, ws["latitude"], ws["longitude"])
        devices_data.append({
            "type": "wifi",
            "id": ws["id"],
            "mac": ws["bssid"],
            "name": ws["ssid"],
            "encryption": ws["encryption"],
            "capabilities": ws["capabilities"],
            "frequency_band": ws["frequency_band"],
            "signal_dbm": ws["signal_dbm"],
            "lat": ws["latitude"],
            "lng": ws["longitude"],
            "first_seen": ws["first_seen"].isoformat() if ws["first_seen"] else None,
            "last_seen": ws["last_seen"].isoformat() if ws["last_seen"] else None,
            "sighting_count": ws["sighting_count"],
            "manufacturer": manufacturer,
            "age": _age_category(ws["last_seen"]),
            "previous_locations": prev_locs,
        })

    for cs in cell_sightings:
        cell_id_val = cs["cell_id"]
        radio = cs["radio_technology"]
        mcc = cs["mcc"] or ""
        mnc = cs["mnc"] or ""
        identifier = f"{radio}:{mcc}:{mnc}:{cell_id_val}" if cell_id_val is not None else f"{radio}:{cs.get('pci', '')}"
        prev_locs = _device_history(identifier, cs["latitude"], cs["longitude"])
        devices_data.append({
            "type": "cell",
            "id": cs["id"],
            "radio": radio,
            "mcc": mcc,
            "mnc": mnc,
            "cell_id": cell_id_val,
            "lac": cs["lac"],
            "tac": cs["tac"],
            "pci": cs["pci"],
            "signal_dbm": cs["signal_dbm"],
            "rsrp": cs["rsrp"],
            "rsrq": cs["rsrq"],
            "snr": cs["snr"],
            "lat": cs["latitude"],
            "lng": cs["longitude"],
            "first_seen": cs["first_seen"].isoformat() if cs["first_seen"] else None,
            "last_seen": cs["last_seen"].isoformat() if cs["last_seen"] else None,
            "sighting_count": cs["sighting_count"],
            "age": _age_category(cs["last_seen"]),
            "previous_locations": prev_locs,
        })

    for ns in nearby_sightings:
        manufacturer = _lookup_manufacturer(ns["address"])
        identifier = ns["address"].upper()
        prev_locs = _device_history(identifier, ns["latitude"], ns["longitude"])
        ble_info = parse_ble_device_info(
            ns.get("manufacturer_data") if "manufacturer_data" in ns else None,
            ns.get("name") if "name" in ns else None,
            ns.get("service_uuids") if "service_uuids" in ns else None,
        )
        devices_data.append({
            "type": "bluetooth",
            "id": ns["id"],
            "mac": ns["address"],
            "name": ns["name"],
            "device_type": ns["device_type"],
            "rssi": ns["rssi"],
            "tx_power": ns["tx_power"],
            "lat": ns["latitude"],
            "lng": ns["longitude"],
            "first_seen": ns["first_seen"].isoformat() if ns["first_seen"] else None,
            "last_seen": ns["last_seen"].isoformat() if ns["last_seen"] else None,
            "sighting_count": ns["sighting_count"],
            "manufacturer": manufacturer,
            "model": ble_info.get("model"),
            "vendor": ble_info.get("vendor"),
            "age": _age_category(ns["last_seen"]),
            "previous_locations": prev_locs,
        })

    # Client devices for filtering
    client_devices = list(Device.objects.values("device_id", "name"))
    client_devices = [{"device_id": str(d["device_id"]), "name": d["name"]} for d in client_devices]

    context = {
        "devices_json": json.dumps(devices_data),
        "client_devices_json": json.dumps(list(client_devices)),
        "total_count": len(devices_data),
        "wifi_count": len(wifi_sightings),
        "cell_count": len(cell_sightings),
        "ble_count": len(nearby_sightings),
    }
    return render(request, "device_map.html", context)


# --- Device search/filter API ---


def device_search_api(request):
    """API endpoint for filtering wardriving device sightings."""
    device_type = request.GET.get("type", "")
    mac_prefix = request.GET.get("mac_prefix", "").upper().strip()
    client_device = request.GET.get("client_device", "")
    date_from = request.GET.get("date_from", "")
    date_to = request.GET.get("date_to", "")

    results = []

    # Build queryset filters
    def apply_common_filters(qs, mac_field, device_field, date_field):
        if mac_prefix:
            qs = qs.filter(**{f"{mac_field}__istartswith": mac_prefix})
        if client_device:
            qs = qs.filter(**{device_field: client_device})
        if date_from:
            try:
                dt_from = datetime.fromisoformat(date_from).replace(tzinfo=dt_timezone.utc)
                qs = qs.filter(**{f"{date_field}__gte": dt_from})
            except ValueError:
                pass
        if date_to:
            try:
                dt_to = datetime.fromisoformat(date_to).replace(tzinfo=dt_timezone.utc)
                qs = qs.filter(**{f"{date_field}__lte": dt_to})
            except ValueError:
                pass
        return qs

    if device_type in ("", "wifi"):
        qs = apply_common_filters(
            WifiSighting.objects.filter(latitude__isnull=False),
            "bssid", "last_device__device_id", "last_seen",
        )
        for ws in qs.values(
            "id", "bssid", "ssid", "encryption", "frequency_band",
            "signal_dbm", "latitude", "longitude", "first_seen", "last_seen",
            "sighting_count", "last_device__name",
        ):
            results.append({
                "type": "wifi",
                "id": ws["id"],
                "mac": ws["bssid"],
                "name": ws["ssid"],
                "encryption": ws["encryption"],
                "frequency_band": ws["frequency_band"],
                "signal_dbm": ws["signal_dbm"],
                "lat": ws["latitude"],
                "lng": ws["longitude"],
                "first_seen": ws["first_seen"].isoformat() if ws["first_seen"] else None,
                "last_seen": ws["last_seen"].isoformat() if ws["last_seen"] else None,
                "sighting_count": ws["sighting_count"],
                "manufacturer": _lookup_manufacturer(ws["bssid"]),
                "client_device": ws["last_device__name"],
                "age": _age_category(ws["last_seen"]),
            })

    if device_type in ("", "cell"):
        qs = apply_common_filters(
            CellTowerSighting.objects.filter(latitude__isnull=False),
            "mcc", "last_device__device_id", "last_seen",
        )
        # MAC prefix doesn't apply to cell towers, skip that filter
        if mac_prefix:
            qs = CellTowerSighting.objects.none()
        for cs in qs.values(
            "id", "radio_technology", "mcc", "mnc", "cell_id", "lac", "tac", "pci",
            "signal_dbm", "rsrp", "rsrq", "snr",
            "latitude", "longitude", "first_seen", "last_seen",
            "sighting_count", "last_device__name",
        ):
            results.append({
                "type": "cell",
                "id": cs["id"],
                "radio": cs["radio_technology"],
                "mcc": cs["mcc"],
                "mnc": cs["mnc"],
                "cell_id": cs["cell_id"],
                "lac": cs["lac"],
                "tac": cs["tac"],
                "pci": cs["pci"],
                "signal_dbm": cs["signal_dbm"],
                "rsrp": cs["rsrp"],
                "rsrq": cs["rsrq"],
                "snr": cs["snr"],
                "lat": cs["latitude"],
                "lng": cs["longitude"],
                "first_seen": cs["first_seen"].isoformat() if cs["first_seen"] else None,
                "last_seen": cs["last_seen"].isoformat() if cs["last_seen"] else None,
                "sighting_count": cs["sighting_count"],
                "client_device": cs["last_device__name"],
                "age": _age_category(cs["last_seen"]),
            })

    if device_type in ("", "bluetooth"):
        qs = apply_common_filters(
            NearbyDeviceSighting.objects.filter(latitude__isnull=False),
            "address", "last_device__device_id", "last_seen",
        )
        for ns in qs.values(
            "id", "address", "name", "device_type", "rssi", "tx_power",
            "manufacturer_data", "service_uuids",
            "latitude", "longitude", "first_seen", "last_seen",
            "sighting_count", "last_device__name",
        ):
            ble_info = parse_ble_device_info(
                ns.get("manufacturer_data"),
                ns.get("name"),
                ns.get("service_uuids"),
            )
            results.append({
                "type": "bluetooth",
                "id": ns["id"],
                "mac": ns["address"],
                "name": ns["name"],
                "device_type": ns["device_type"],
                "rssi": ns["rssi"],
                "tx_power": ns["tx_power"],
                "lat": ns["latitude"],
                "lng": ns["longitude"],
                "first_seen": ns["first_seen"].isoformat() if ns["first_seen"] else None,
                "last_seen": ns["last_seen"].isoformat() if ns["last_seen"] else None,
                "sighting_count": ns["sighting_count"],
                "manufacturer": _lookup_manufacturer(ns["address"]),
                "model": ble_info.get("model"),
                "vendor": ble_info.get("vendor"),
                "client_device": ns["last_device__name"],
                "age": _age_category(ns["last_seen"]),
            })

    return JsonResponse({"results": results, "count": len(results)})


# --- Admin API for mobile app ---


@csrf_exempt
@require_http_methods(["POST"])
def admin_login(request):
    """Authenticate staff credentials. Returns session cookie for subsequent admin API calls."""
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    username = data.get("username", "")
    password = data.get("password", "")
    user = authenticate(request, username=username, password=password)
    if user is not None and user.is_staff:
        login(request, user)
        return JsonResponse({"authenticated": True, "username": user.username})
    return JsonResponse({"authenticated": False, "error": "Invalid credentials or not staff"}, status=401)


@staff_member_required
@require_http_methods(["POST"])
def admin_create_device(request):
    """Create a new client device from the admin app. Returns JSON with
    device_id, secret_key, and the QR payload for provisioning."""
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    name = (data.get("name") or "").strip()
    if not name:
        return JsonResponse({"error": "Missing device name"}, status=400)

    secret_key = base64.b64encode(secrets.token_bytes(32)).decode()
    device = Device.objects.create(name=name, secret_key=secret_key)

    return JsonResponse({
        "device_id": str(device.device_id),
        "secret_key": device.secret_key,
        "name": device.name,
        "qr_payload": json.dumps({
            "host": settings.TRACKER_SERVER_HOST,
            "port": settings.TRACKER_SERVER_PORT,
            "cert_pin": settings.TRACKER_CERT_PIN_HASH,
            "device_id": str(device.device_id),
            "secret_key": device.secret_key,
        }),
    })


@staff_member_required
@require_http_methods(["GET"])
def admin_client_locations(request):
    """Returns last known location of all registered client devices."""
    devices = Device.objects.all()
    locations = []
    for device in devices:
        latest = device.locations.order_by("-created_at", "-id").first()
        if latest and latest.latitude is not None and latest.longitude is not None:
            locations.append({
                "device_id": str(device.device_id),
                "name": device.name,
                "latitude": latest.latitude,
                "longitude": latest.longitude,
                "accuracy": latest.accuracy,
                "timestamp": latest.timestamp.isoformat(),
                "triggered_by": latest.triggered_by,
                "location_source": latest.location_source,
            })
    return JsonResponse({"clients": locations})


@staff_member_required
@require_http_methods(["GET"])
def admin_nearby_devices(request):
    """Returns wardriving device sightings within a radius of a given location."""
    try:
        lat = float(request.GET.get("lat", 0))
        lng = float(request.GET.get("lng", 0))
        radius_m = float(request.GET.get("radius", 50))
    except (TypeError, ValueError):
        return JsonResponse({"error": "Invalid lat/lng/radius params"}, status=400)

    # Simple bounding box approximation (good enough for small radii)
    # 1 degree latitude ≈ 111,000 metres
    lat_delta = radius_m / 111000.0
    lng_delta = radius_m / (111000.0 * abs(math.cos(math.radians(lat))) if lat != 0 else 1)

    results = []

    for ws in WifiSighting.objects.filter(
        latitude__isnull=False,
        latitude__range=(lat - lat_delta, lat + lat_delta),
        longitude__range=(lng - lng_delta, lng + lng_delta),
    ).values("id", "bssid", "ssid", "encryption", "signal_dbm", "latitude", "longitude", "last_seen", "sighting_count"):
        results.append({
            "type": "wifi",
            "mac": ws["bssid"],
            "name": ws["ssid"],
            "encryption": ws["encryption"],
            "signal_dbm": ws["signal_dbm"],
            "lat": ws["latitude"],
            "lng": ws["longitude"],
            "last_seen": ws["last_seen"].isoformat() if ws["last_seen"] else None,
            "sighting_count": ws["sighting_count"],
            "manufacturer": _lookup_manufacturer(ws["bssid"]),
        })

    for cs in CellTowerSighting.objects.filter(
        latitude__isnull=False,
        latitude__range=(lat - lat_delta, lat + lat_delta),
        longitude__range=(lng - lng_delta, lng + lng_delta),
    ).values("id", "radio_technology", "mcc", "mnc", "cell_id", "signal_dbm", "latitude", "longitude", "last_seen", "sighting_count"):
        results.append({
            "type": "cell",
            "radio": cs["radio_technology"],
            "mcc": cs["mcc"],
            "mnc": cs["mnc"],
            "cell_id": cs["cell_id"],
            "signal_dbm": cs["signal_dbm"],
            "lat": cs["latitude"],
            "lng": cs["longitude"],
            "last_seen": cs["last_seen"].isoformat() if cs["last_seen"] else None,
            "sighting_count": cs["sighting_count"],
        })

    for ns in NearbyDeviceSighting.objects.filter(
        latitude__isnull=False,
        latitude__range=(lat - lat_delta, lat + lat_delta),
        longitude__range=(lng - lng_delta, lng + lng_delta),
    ).values("id", "address", "name", "device_type", "rssi", "tx_power",
              "manufacturer_data", "service_uuids",
              "latitude", "longitude", "last_seen", "sighting_count"):
        ble_info = parse_ble_device_info(
            ns.get("manufacturer_data"),
            ns.get("name"),
            ns.get("service_uuids"),
        )
        results.append({
            "type": "bluetooth",
            "mac": ns["address"],
            "name": ns["name"],
            "device_type": ns["device_type"],
            "rssi": ns["rssi"],
            "tx_power": ns["tx_power"],
            "lat": ns["latitude"],
            "lng": ns["longitude"],
            "last_seen": ns["last_seen"].isoformat() if ns["last_seen"] else None,
            "sighting_count": ns["sighting_count"],
            "manufacturer": _lookup_manufacturer(ns["address"]),
            "model": ble_info.get("model"),
            "vendor": ble_info.get("vendor"),
        })

    return JsonResponse({"devices": results, "count": len(results)})


@staff_member_required
@require_http_methods(["GET"])
def admin_device_history(request):
    """Returns all historical locations for a given device identifier.
    Excludes locations within 50m of the provided reference lat/lng."""
    identifier = request.GET.get("mac", "").upper().strip()
    ref_lat = request.GET.get("lat")
    ref_lng = request.GET.get("lng")
    if not identifier:
        return JsonResponse({"error": "Missing mac parameter"}, status=400)

    histories = DeviceLocationHistory.objects.filter(
        device_identifier=identifier
    ).order_by("-last_seen")

    locations = []
    for h in histories:
        loc = {
            "latitude": h.latitude,
            "longitude": h.longitude,
            "first_seen": h.first_seen.isoformat(),
            "last_seen": h.last_seen.isoformat(),
            "sighting_count": h.sighting_count,
        }
        # Skip if within 50m of the reference location
        if ref_lat and ref_lng:
            try:
                ref_lat_f = float(ref_lat)
                ref_lng_f = float(ref_lng)
                distance = _haversine(h.latitude, h.longitude, ref_lat_f, ref_lng_f)
                if distance < 50:
                    continue
            except (TypeError, ValueError):
                pass
        locations.append(loc)

    return JsonResponse({"identifier": identifier, "locations": locations, "count": len(locations)})


def _haversine(lat1, lng1, lat2, lng2):
    """Calculate distance in metres between two lat/lng points."""
    import math
    r = 6371000  # Earth radius in metres
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# --- CVE lookup ---


# Common OUI manufacturer names -> cve-search vendor names.
# The OUI registry uses full legal names; cve-search uses lowercase CPE vendor names.
_VENDOR_ALIASES = {
    "apple": "apple",
    "samsung": "samsung",
    "cisco": "cisco",
    "intel": "intel",
    "qualcomm": "qualcomm",
    "huawei": "huawei",
    "tp-link": "tp-link",
    "netgear": "netgear",
    "d-link": "d-link",
    "linksys": "linksys",
    "asus": "asus",
    "belkin": "belkin",
    "sony": "sony",
    "microsoft": "microsoft",
    "google": "google",
    "xiaomi": "xiaomi",
    "oneplus": "oneplus",
    "oppo": "oppo",
    "huawei technologies": "huawei",
    "lg electronics": "lg",
    "motorola": "motorola",
    "nokia": "nokia",
    "ericsson": "ericsson",
    "juniper": "juniper",
    "aruba": "aruba",
    "ruckus": "ruckus",
    "ubiquiti": "ubiquiti",
    "fortinet": "fortinet",
    "zyxel": "zyxel",
    "tenda": "tenda",
    "mercury": "mercury",
    "realtek": "realtek",
    "broadcom": "broadcom",
    "mediatek": "mediatek",
    "marvell": "marvell",
    "cypress": "cypress",
}


def _normalize_vendor(manufacturer_name):
    """Normalize an OUI manufacturer name to a cve-search vendor name."""
    if not manufacturer_name:
        return None
    name = manufacturer_name.lower().strip()
    # Try exact alias match first
    if name in _VENDOR_ALIASES:
        return _VENDOR_ALIASES[name]
    # Try prefix match (e.g. "Apple, Inc." -> "apple")
    for alias_key, vendor in _VENDOR_ALIASES.items():
        if name.startswith(alias_key):
            return vendor
    # Try the first word as a fallback
    first_word = name.split(",")[0].split()[0] if name.split() else None
    if first_word and first_word in _VENDOR_ALIASES:
        return _VENDOR_ALIASES[first_word]
    # Fall back to the first word as-is (cve-search may know it)
    return first_word


@staff_member_required
@require_http_methods(["GET"])
def cve_lookup(request):
    """Look up CVEs for a manufacturer via the local cve-search API.
    Query params: manufacturer=<OUI org name>  (e.g. "Apple, Inc.")
    Returns: {vendor, products, cves: [{id, cvss, published, summary, references}]}
    """
    manufacturer = request.GET.get("manufacturer", "").strip()
    if not manufacturer:
        return JsonResponse({"error": "Missing manufacturer parameter"}, status=400)

    vendor = _normalize_vendor(manufacturer)
    if not vendor:
        return JsonResponse({"error": "Could not normalize manufacturer name"}, status=400)

    api_base = getattr(settings, "CVE_API_BASE", "http://127.0.0.1:5000")

    # Step 1: get the list of products for this vendor
    try:
        browse_url = f"{api_base}/api/browse/{vendor}"
        browse_req = urllib.request.Request(browse_url, method="GET")
        with urllib.request.urlopen(browse_req, timeout=10) as resp:
            browse_data = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return JsonResponse({"vendor": vendor, "products": [], "cves": [],
                                 "message": f"Vendor '{vendor}' not found in CVE database"})
        logger.warning("CVE browse API error: %s", e)
        return JsonResponse({"error": f"CVE API error: {e}"}, status=502)
    except (urllib.error.URLError, OSError) as e:
        return JsonResponse({"error": f"Cannot reach CVE API at {api_base}: {e}"}, status=502)

    products = browse_data.get("product", [])
    if not products:
        return JsonResponse({"vendor": vendor, "products": [], "cves": [],
                             "message": f"No products found for vendor '{vendor}'"})

    # Step 2: search for CVEs across the vendor's products.
    # Query the vendor's first few products to get representative CVEs —
    # querying all products would be too slow.
    cves_by_id = {}
    for product in products[:5]:
        try:
            search_url = f"{api_base}/api/search/{vendor}/{product}"
            search_req = urllib.request.Request(search_url, method="GET")
            with urllib.request.urlopen(search_req, timeout=15) as resp:
                results = json.loads(resp.read().decode())
        except (urllib.error.HTTPError, urllib.error.URLError, OSError):
            continue
        if not isinstance(results, list):
            continue
        for cve in results:
            cve_id = cve.get("id")
            if not cve_id or cve_id in cves_by_id:
                continue
            cves_by_id[cve_id] = {
                "id": cve_id,
                "cvss": cve.get("cvss"),
                "published": cve.get("Published"),
                "modified": cve.get("Modified"),
                "summary": (cve.get("summary") or "")[:300],
                "references": cve.get("references", [])[:5],
            }

    # Sort by CVSS (descending), then by publish date (newest first)
    cve_list = sorted(
        cves_by_id.values(),
        key=lambda c: (-(c["cvss"] or 0), c["published"] or ""),
    )

    return JsonResponse({
        "vendor": vendor,
        "manufacturer": manufacturer,
        "products": products,
        "cve_count": len(cve_list),
        "cves": cve_list[:50],
    })


# --- App update pipeline ---


APK_DIR = getattr(settings, "APK_DIR", os.path.join(settings.BASE_DIR, "app_updates"))


@csrf_exempt
@require_http_methods(["GET"])
def app_latest_version(request):
    """Returns the latest app version info for update checking."""
    version_file = os.path.join(APK_DIR, "version.json")
    if not os.path.exists(version_file):
        return JsonResponse({"version_code": 0, "version_name": "0.0", "url": ""})
    with open(version_file) as f:
        data = json.load(f)
    data["url"] = f"{settings.TRACKER_SERVER_HOST}:{settings.TRACKER_SERVER_PORT}/apk/latest.apk"
    return JsonResponse(data)


@csrf_exempt
@require_http_methods(["GET"])
def app_download_apk(request):
    """Serves the latest signed APK for download."""
    apk_path = os.path.join(APK_DIR, "latest.apk")
    if not os.path.exists(apk_path):
        raise Http404("No APK available")
    return FileResponse(
        open(apk_path, "rb"),
        content_type="application/vnd.android.package-archive",
        as_attachment=True,
        filename="location-tracker-latest.apk",
    )


# --- Client route search ---


@csrf_exempt
@require_http_methods(["GET"])
def client_route_search(request):
    """Search for client location updates filtered by device and date range.
    Returns points per device ordered by time, ready to be drawn as route
    polylines on the map.

    Query params:
      device_id  — UUID of a specific device (optional, omit for all)
      date_from  — ISO datetime (optional)
      date_to    — ISO datetime (optional)

    Returns: {routes: [{device_id, name, color, points: [{lat,lng,timestamp,...}]}]}
    """
    device_id = request.GET.get("device_id", "").strip()
    date_from = request.GET.get("date_from", "").strip()
    date_to = request.GET.get("date_to", "").strip()

    qs = LocationUpdate.objects.filter(latitude__isnull=False, longitude__isnull=False)

    if device_id:
        qs = qs.filter(device__device_id=device_id)

    if date_from:
        try:
            dt_from = datetime.fromisoformat(date_from).replace(tzinfo=dt_timezone.utc)
            qs = qs.filter(timestamp__gte=dt_from)
        except ValueError:
            pass

    if date_to:
        try:
            dt_to = datetime.fromisoformat(date_to).replace(tzinfo=dt_timezone.utc)
            qs = qs.filter(timestamp__lte=dt_to)
        except ValueError:
            pass

    qs = qs.order_by("device_id", "created_at", "sequence", "id")

    # Group points by device and assign colors
    COLORS = ["#4ade80", "#3b82f6", "#f59e0b", "#ef4444", "#a855f7",
              "#06b6d4", "#ec4899", "#84cc16", "#f97316", "#6366f1"]

    routes_map = {}
    for lu in qs.select_related("device"):
        key = str(lu.device.device_id)
        if key not in routes_map:
            routes_map[key] = {
                "device_id": key,
                "name": lu.device.name,
                "color": COLORS[len(routes_map) % len(COLORS)],
                "points": [],
            }
        routes_map[key]["points"].append({
            "lat": lu.latitude,
            "lng": lu.longitude,
            "accuracy": lu.accuracy,
            "timestamp": lu.timestamp.isoformat(),
            "received_at": lu.created_at.isoformat(),
            "triggered_by": lu.triggered_by,
            "location_source": lu.location_source,
        })

    return JsonResponse({
        "routes": list(routes_map.values()),
        "total_points": sum(len(r["points"]) for r in routes_map.values()),
    })


# --- App log ingestion ---


@csrf_exempt
@require_http_methods(["POST"])
def receive_app_logs(request):
    """Receive a batch of logs from the Android app. The request is HMAC-signed
    so request.device is set by the middleware. Body format:
    {"logs": [{"timestamp": <iso>, "level": "INFO", "tag": "app", "message": "..."}, ...]}
    """
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return JsonResponse({"error": "Invalid JSON"}, status=400)

    logs = data.get("logs", [])
    if not isinstance(logs, list) or not logs:
        return JsonResponse({"status": "ok", "received": 0})

    created = 0
    for entry in logs:
        ts_str = entry.get("timestamp")
        if not ts_str:
            continue
        try:
            ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        except (TypeError, ValueError):
            continue
        AppLog.objects.create(
            device=request.device,
            timestamp=ts,
            level=entry.get("level", "INFO")[:10],
            tag=entry.get("tag", "app")[:50],
            message=entry.get("message", "")[:10000],
        )
        created += 1

    logger.info("LOGS received device=%s count=%d", request.device.name, created)
    return JsonResponse({"status": "ok", "received": created})
