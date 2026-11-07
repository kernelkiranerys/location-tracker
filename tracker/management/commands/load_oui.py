"""Download and load the IEEE OUI database into the OuiEntry table.

Usage:
  python manage.py load_oui               # download from nmap GitHub mirror
  python manage.py load_oui --file path   # load from local file
"""
import re
import urllib.request

from django.core.management.base import BaseCommand
from tracker.models import OuiEntry

OUI_URLS = [
    "https://raw.githubusercontent.com/nmap/nmap/master/nmap-mac-prefixes",
    "https://standards-ieee.org/products-programs/regauth/oui/oui.txt",
]


class Command(BaseCommand):
    help = "Download and load the IEEE OUI database"

    def add_arguments(self, parser):
        parser.add_argument("--file", type=str, help="Load from local file instead of downloading")

    def handle(self, *args, **options):
        file_path = options.get("file")

        if file_path:
            self.stdout.write(f"Loading OUI from local file: {file_path}")
            with open(file_path, "r", encoding="ascii", errors="replace") as f:
                data = f.read()
        else:
            data = None
            for url in OUI_URLS:
                self.stdout.write(f"Trying {url}...")
                try:
                    with urllib.request.urlopen(url, timeout=60) as resp:
                        data = resp.read().decode("ascii", errors="replace")
                    self.stdout.write(f"Downloaded {len(data)} bytes")
                    break
                except Exception as e:
                    self.stderr.write(f"Failed: {e}")
            if data is None:
                self.stderr.write(self.style.ERROR("All download sources failed"))
                self.stderr.write("Tip: download manually and use --file /path/to/oui.txt")
                return

        entries = []

        # Try IEEE format first: "AA-BB-CC   (hex)   Organization Name"
        ieee_pattern = re.compile(
            r"^([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})\s+\(hex\)\s+(.+)$",
            re.MULTILINE,
        )
        for match in ieee_pattern.finditer(data):
            prefix = f"{match.group(1).upper()}:{match.group(2).upper()}:{match.group(3).upper()}"
            org = match.group(4).strip()
            entries.append(OuiEntry(prefix=prefix, organization=org, registry="OUI"))

        # If IEEE format didn't match, try nmap format: "AABBCC Organization"
        if not entries:
            self.stdout.write("IEEE format not found, trying nmap format...")
            nmap_pattern = re.compile(
                r"^([0-9A-Fa-f]{6})\s+(\S.+)$",
                re.MULTILINE,
            )
            for match in nmap_pattern.finditer(data):
                hex_str = match.group(1).upper()
                prefix = f"{hex_str[0:2]}:{hex_str[2:4]}:{hex_str[4:6]}"
                org = match.group(2).strip()
                entries.append(OuiEntry(prefix=prefix, organization=org, registry="OUI"))

        if not entries:
            self.stderr.write(self.style.ERROR("No OUI entries found in data"))
            return

        self.stdout.write(f"Found {len(entries)} OUI entries, loading...")

        OuiEntry.objects.all().delete()
        OuiEntry.objects.bulk_create(entries, batch_size=500)

        self.stdout.write(self.style.SUCCESS(f"Loaded {len(entries)} OUI entries"))
