import uuid

from django.db import models


class Device(models.Model):
    device_id = models.UUIDField(default=uuid.uuid4, editable=False, unique=True)
    name = models.CharField(max_length=100)
    secret_key = models.CharField(max_length=64)
    created_at = models.DateTimeField(auto_now_add=True)
    last_seen = models.DateTimeField(null=True, blank=True)
    privacy_until = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"{self.name} ({self.device_id})"


class LocationUpdate(models.Model):
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name="locations")
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    accuracy = models.FloatField(null=True, blank=True)
    altitude = models.FloatField(null=True, blank=True)
    speed = models.FloatField(null=True, blank=True)
    bearing = models.FloatField(null=True, blank=True)
    timestamp = models.DateTimeField()
    created_at = models.DateTimeField(auto_now_add=True)
    triggered_by = models.CharField(
        max_length=20,
        choices=[
            ("scheduled", "Scheduled (20-min)"),
            ("on_demand", "Server-requested"),
            ("privacy_override", "Server overrode privacy mode"),
        ],
        default="scheduled",
    )
    location_source = models.CharField(
        max_length=20,
        choices=[
            ("google_wifi", "Google WiFi geolocation"),
            ("gps", "Client GPS fix (fallback)"),
        ],
        default="gps",
    )
    gps_latitude = models.FloatField(null=True, blank=True)
    gps_longitude = models.FloatField(null=True, blank=True)
    gps_accuracy = models.FloatField(null=True, blank=True)
    sequence = models.BigIntegerField(default=0)

    class Meta:
        ordering = ["-created_at", "-timestamp", "-id"]
        indexes = [
            models.Index(fields=["device", "-timestamp"]),
            models.Index(fields=["device", "created_at", "sequence"]),
        ]


# --- Original WiFi network data (per location update) ---


class WifiNetwork(models.Model):
    location_update = models.ForeignKey(LocationUpdate, on_delete=models.CASCADE, related_name="wifi_networks")
    ssid = models.CharField(max_length=100, blank=True)
    bssid = models.CharField(max_length=17, blank=True)
    signal_dbm = models.IntegerField(null=True, blank=True)
    frequency_mhz = models.IntegerField(null=True, blank=True)
    is_connected = models.BooleanField(default=False)
    channel = models.IntegerField(null=True, blank=True)
    # New war-driving fields
    capabilities = models.TextField(blank=True, default="")
    encryption = models.CharField(max_length=20, default="UNKNOWN")
    frequency_band = models.CharField(max_length=10, blank=True, default="")


# --- War-driving: aggregated device sightings ---


class WifiSighting(models.Model):
    """Aggregated WiFi AP sighting. One row per unique BSSID, updated in place."""
    bssid = models.CharField(max_length=17, db_index=True)
    ssid = models.CharField(max_length=100, blank=True, default="")
    encryption = models.CharField(max_length=20, default="UNKNOWN")
    capabilities = models.TextField(blank=True, default="")
    frequency_band = models.CharField(max_length=10, blank=True, default="")
    signal_dbm = models.IntegerField(null=True, blank=True)
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    first_seen = models.DateTimeField(auto_now_add=True)
    last_seen = models.DateTimeField(db_index=True)
    sighting_count = models.IntegerField(default=1)
    last_device = models.ForeignKey(Device, on_delete=models.SET_NULL, null=True, blank=True, related_name="wifi_sightings")

    class Meta:
        indexes = [
            models.Index(fields=["bssid"]),
            models.Index(fields=["last_seen"]),
            models.Index(fields=["ssid"]),
        ]


class CellTowerSighting(models.Model):
    """Aggregated cell tower sighting. Keyed by radio+MCC+MNC+cell_id when available."""
    radio_technology = models.CharField(max_length=10, db_index=True)
    mcc = models.CharField(max_length=5, blank=True, default="")
    mnc = models.CharField(max_length=5, blank=True, default="")
    cell_id = models.BigIntegerField(null=True, blank=True)
    lac = models.IntegerField(null=True, blank=True)
    tac = models.IntegerField(null=True, blank=True)
    pci = models.IntegerField(null=True, blank=True)
    signal_dbm = models.IntegerField(null=True, blank=True)
    rsrp = models.IntegerField(null=True, blank=True)
    rsrq = models.IntegerField(null=True, blank=True)
    snr = models.IntegerField(null=True, blank=True)
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    first_seen = models.DateTimeField(auto_now_add=True)
    last_seen = models.DateTimeField(db_index=True)
    sighting_count = models.IntegerField(default=1)
    last_device = models.ForeignKey(Device, on_delete=models.SET_NULL, null=True, blank=True, related_name="cell_sightings")

    class Meta:
        indexes = [
            models.Index(fields=["radio_technology"]),
            models.Index(fields=["mcc", "mnc"]),
            models.Index(fields=["last_seen"]),
        ]


class NearbyDeviceSighting(models.Model):
    """Aggregated BLE/nearby device sighting. Keyed by MAC address."""
    address = models.CharField(max_length=17, db_index=True)
    name = models.CharField(max_length=200, blank=True, default="")
    device_type = models.CharField(max_length=20, default="BLE")
    rssi = models.IntegerField(null=True, blank=True)
    tx_power = models.IntegerField(null=True, blank=True)
    manufacturer_data = models.TextField(blank=True, default="")
    service_uuids = models.TextField(blank=True, default="")
    latitude = models.FloatField(null=True, blank=True)
    longitude = models.FloatField(null=True, blank=True)
    first_seen = models.DateTimeField(auto_now_add=True)
    last_seen = models.DateTimeField(db_index=True)
    sighting_count = models.IntegerField(default=1)
    last_device = models.ForeignKey(Device, on_delete=models.SET_NULL, null=True, blank=True, related_name="nearby_sightings")

    class Meta:
        indexes = [
            models.Index(fields=["address"]),
            models.Index(fields=["last_seen"]),
            models.Index(fields=["name"]),
        ]


# --- OUI lookup (IEEE MAC address manufacturer database) ---


class OuiEntry(models.Model):
    """IEEE OUI registry entry. Maps MAC prefix (first 3 octets) to manufacturer."""
    prefix = models.CharField(max_length=8, unique=True, db_index=True)  # e.g. "AA:BB:CC"
    organization = models.CharField(max_length=200)
    registry = models.CharField(max_length=20, default="OUI")

    class Meta:
        indexes = [models.Index(fields=["prefix"])]


# --- Device location history (for "other locations" in admin nearby view) ---


class DeviceLocationHistory(models.Model):
    """Records every location a wardriving device has been seen at.
    One row per (device_identifier, location) combination — updated in place
    with the latest sighting time."""
    device_type = models.CharField(max_length=20, db_index=True)  # "wifi", "cell", "bluetooth"
    device_identifier = models.CharField(max_length=100, db_index=True)  # BSSID, MAC, or cell key
    latitude = models.FloatField()
    longitude = models.FloatField()
    first_seen = models.DateTimeField(auto_now_add=True)
    last_seen = models.DateTimeField(db_index=True)
    sighting_count = models.IntegerField(default=1)

    class Meta:
        indexes = [
            models.Index(fields=["device_identifier"]),
            models.Index(fields=["device_type", "device_identifier"]),
            models.Index(fields=["last_seen"]),
        ]
        unique_together = [("device_identifier", "latitude", "longitude")]


# --- Non-wardriving models ---


class UsedNonce(models.Model):
    value = models.CharField(max_length=64, unique=True, db_index=True)
    expires_at = models.DateTimeField()

    class Meta:
        indexes = [models.Index(fields=["expires_at"])]


class PendingRequest(models.Model):
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name="pending_requests")
    fulfilled = models.BooleanField(default=False)
    fulfilled_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)


class AppLog(models.Model):
    """Logs received from the Android app for debugging."""
    device = models.ForeignKey(Device, on_delete=models.CASCADE, related_name="app_logs")
    timestamp = models.DateTimeField(help_text="Client-side log timestamp")
    level = models.CharField(max_length=10, default="INFO")
    tag = models.CharField(max_length=50, default="app")
    message = models.TextField()
    received_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-timestamp"]
        indexes = [
            models.Index(fields=["device", "-timestamp"]),
            models.Index(fields=["level"]),
            models.Index(fields=["tag"]),
        ]
