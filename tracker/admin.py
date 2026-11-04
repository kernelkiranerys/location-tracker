from django.contrib import admin
from django.urls import reverse
from django.utils.html import format_html

from .models import Device, LocationUpdate, PendingRequest, UsedNonce, WifiNetwork, AppLog

admin.site.site_header = "Location Tracker Admin"
admin.site.site_title = "Location Tracker"
admin.site.index_title = "Administration"


@admin.register(Device)
class DeviceAdmin(admin.ModelAdmin):
    list_display = ("name", "device_id", "last_seen", "privacy_until", "qr_link")
    readonly_fields = ("qr_link",)

    @admin.display(description="QR code")
    def qr_link(self, obj):
        url = reverse("device-qr", args=[obj.device_id])
        return format_html('<a href="{}" target="_blank">View QR</a>', url)


@admin.register(LocationUpdate)
class LocationUpdateAdmin(admin.ModelAdmin):
    list_display = ("device", "created_at", "timestamp", "latitude", "longitude", "location_source", "triggered_by")
    list_filter = ("location_source", "triggered_by")
    date_hierarchy = "created_at"


admin.site.register(WifiNetwork)
admin.site.register(UsedNonce)


@admin.register(PendingRequest)
class PendingRequestAdmin(admin.ModelAdmin):
    list_display = ("device", "fulfilled", "created_at", "fulfilled_at")
    list_filter = ("fulfilled",)


@admin.register(AppLog)
class AppLogAdmin(admin.ModelAdmin):
    list_display = ("device", "timestamp", "level", "tag", "message")
    list_filter = ("level", "tag", "device")
    date_hierarchy = "timestamp"
    search_fields = ("message", "tag")
