from django.urls import path

from . import views

urlpatterns = [
    path("api/location/", views.receive_location, name="receive-location"),
    path("api/logs/", views.receive_app_logs, name="receive-logs"),
    path("api/privacy/", views.privacy_request, name="privacy-request"),
    path("api/pending-request/", views.pending_request, name="pending-request"),
    path("api/trigger/<uuid:device_id>/", views.trigger_location_request, name="trigger-location"),
    path("map/", views.map_view, name="map"),
    path("device-map/", views.device_map_view, name="device-map"),
    path("api/device-search/", views.device_search_api, name="device-search"),
    path("api/admin-login/", views.admin_login, name="admin-login"),
    path("api/admin/create-device/", views.admin_create_device, name="admin-create-device"),
    path("api/admin/client-locations/", views.admin_client_locations, name="admin-client-locations"),
    path("api/admin/nearby-devices/", views.admin_nearby_devices, name="admin-nearby-devices"),
    path("api/admin/device-history/", views.admin_device_history, name="admin-device-history"),
    path("api/cves/", views.cve_lookup, name="cve-lookup"),
    path("api/route-search/", views.client_route_search, name="route-search"),
    path("api/app-version/", views.app_latest_version, name="app-version"),
    path("apk/latest.apk", views.app_download_apk, name="app-download"),
    path("register/", views.register_device, name="register-device"),
    path("register/<uuid:device_id>/qr/", views.device_qr, name="device-qr"),
]
