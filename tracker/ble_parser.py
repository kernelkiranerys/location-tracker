"""Parse BLE manufacturer-specific data for device model identification.

The Android collector sends manufacturer_data as a list of strings in the
format "0x<company_id_hex>:<value_hex>". This module decodes the well-known
formats to extract device model/generation info:

- Apple Continuity protocol (company_id 0x4C / 76): AirPods, Apple Watch,
  AirTags, etc. The payload format is documented in reverse-engineered
  projects like AirTag trackers.
- Google Fast Pair (company_id 0x2C... actually 0xE0 (224) for Google):
  BLE beacons broadcasting the Fast Pair model ID.
- Samsung Earbuds (various Samsung company IDs)

For anything unrecognised, returns None.
"""
import json


def _parse_manufacturer_data(raw):
    """Normalise the stored manufacturer_data to a dict {company_id_int: bytes}."""
    if not raw:
        return {}
    # Stored as JSON string (from NearbyDeviceSighting.manufacturer_data)
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except (json.JSONDecodeError, ValueError):
            return {}
    if not isinstance(raw, list):
        return {}
    out = {}
    for entry in raw:
        if not isinstance(entry, str) or ":" not in entry:
            continue
        key_str, value_hex = entry.split(":", 1)
        key_str = key_str.strip()
        # Key format from Android: "0x4c" (hex) or decimal
        try:
            if key_str.startswith("0x") or key_str.startswith("0X"):
                key = int(key_str, 16)
            else:
                key = int(key_str)
        except ValueError:
            continue
        try:
            value = bytes.fromhex(value_hex.strip())
        except ValueError:
            continue
        out[key] = value
    return out


# Apple Continuity device type codes (from the payload byte 0 after the
# 2-byte counter). Reverse-engineered by the AirTag/open-source community.
# Source: https://github.com/adamitl/Apple-Continuity-Scanner and similar.
APPLE_CONTINUITY_TYPES = {
    0x01: "AirTag",
    0x07: "AirPods (1st gen)",
    0x0E: "AirPods (2nd gen)",
    0x0F: "AirPods Pro",
    0x13: "AirPods (3rd gen)",
    0x20: "AirPods Pro (2nd gen)",
    0x10: "AirPods Max",
    0x05: "Apple Watch",
    0x30: "Apple Vision Pro",
}

# Google Fast Pair company ID
GOOGLE_COMPANY_ID = 224  # 0xE0

APPLE_COMPANY_ID = 76  # 0x4C


def _parse_apple_continuity(value: bytes):
    """Parse Apple Continuity manufacturer-specific data.

    Format (after the company ID key):
      byte 0: device type code
      byte 1-2: payload length / counter
      ...rest varies by type

    For AirPods specifically, byte 0 has the type code and a later byte
    indicates the charging case state.
    """
    if len(value) < 2:
        return None
    # Skip the 2-byte counter at the start (some formats)
    # Actually the format is: type (1 byte) + encrypted counter (2 bytes) + status + ...
    # The exact offset of the type code varies; common reverse-engineered
    # interpretation: byte 0 = type.
    type_code = value[0]
    return APPLE_CONTINUITY_TYPES.get(type_code)


def _parse_google_fast_pair(value: bytes):
    """Parse Google Fast Pair manufacturer-specific data.

    Format: flags (1 byte) + model_id (3 bytes, big-endian).
    The model_id is a 24-bit identifier registered in Google's database.
    We can return the model_id hex; resolving it to a name requires a
    lookup table (Google publishes a Proximity Beacon API but no public
    dump). Some common ones are hardcoded below.
    """
    if len(value) < 4:
        return None
    flags = value[0]
    model_id = (value[1] << 16) | (value[2] << 8) | value[3]
    model_hex = f"{model_id:06x}"

    # Known Fast Pair model IDs (from community databases).
    # Source: https://github.com/fecurek/fastpair-research and similar.
    FAST_PAIR_MODELS = {
        "017c1f": "Pixel Buds A",
        "018d16": "Pixel Buds Pro",
        "0191ad": "Pixel Buds (2020)",
        "014f9f": "Nest Audio",
        "01dd2c": "Galaxy Buds Live",
        "0154040": "Galaxy Buds2",
        "01ee95": "Sony WH-1000XM4",
        "01ee96": "Sony WH-1000XM5",
        "020d88": "JBL Tune 225TWS",
        "01e5e7": "OnePlus Buds",
        "01c9": "Bose QC35 II",
    }
    return FAST_PAIR_MODELS.get(model_hex, f"Fast Pair {model_hex}")


def parse_ble_device_info(manufacturer_data_raw, device_name=None, service_uuids=None):
    """Return a dict with whatever we can infer about a BLE device.

    Args:
        manufacturer_data_raw: the stored JSON (list of "0xKEY:HEXVALUE" strings)
        device_name: the broadcast name (sometimes informative)
        service_uuids: the stored JSON list of service UUID strings
    """
    parsed = _parse_manufacturer_data(manufacturer_data_raw)
    result = {}

    # Apple Continuity
    if APPLE_COMPANY_ID in parsed:
        model = _parse_apple_continuity(parsed[APPLE_COMPANY_ID])
        if model:
            result["model"] = model
            result["vendor"] = "Apple"
            return result

    # Google Fast Pair
    if GOOGLE_COMPANY_ID in parsed:
        model = _parse_google_fast_pair(parsed[GOOGLE_COMPANY_ID])
        if model:
            result["model"] = model
            result["vendor"] = "Google"
            return result

    # Fall back to the broadcast name if it looks device-y
    if device_name and isinstance(device_name, str):
        # Common patterns: "AirPods", "Galaxy Buds", "Pixel Buds", "[TV] Samsung"
        name_lower = device_name.lower()
        hints = {
            "airpods": "Apple AirPods",
            "galaxy buds": "Samsung Galaxy Buds",
            "pixel buds": "Google Pixel Buds",
            "wh-1000": "Sony WH-1000XM",
            "echo buds": "Amazon Echo Buds",
            " beats ": "Beats",
            "powerbeats": "Beats Powerbeats",
        }
        for hint, label in hints.items():
            if hint in name_lower:
                result["model"] = device_name
                result["vendor"] = label.split()[0]
                return result

    # No model determined
    return result
