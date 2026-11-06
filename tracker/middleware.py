import hashlib
import hmac
import time
from datetime import timedelta

from django.http import JsonResponse
from django.utils import timezone

from .models import Device, UsedNonce


class HMACAuthMiddleware:
    EXCLUDED_PATHS = ["/admin/", "/static/", "/map/", "/device-map/", "/api/trigger/", "/api/device-search/", "/api/admin-login/", "/api/admin/", "/api/cves/", "/api/route-search/", "/api/app-version/", "/apk/", "/register/"]
    MAX_SKEW_SECONDS = 300

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        for path in self.EXCLUDED_PATHS:
            if request.path.startswith(path):
                return self.get_response(request)

        device_id = request.headers.get("X-Device-Id")
        timestamp = request.headers.get("X-Timestamp")
        nonce = request.headers.get("X-Nonce")
        signature = request.headers.get("X-Signature")

        if not all([device_id, timestamp, nonce, signature]):
            return JsonResponse({"error": "Missing auth headers"}, status=401)

        try:
            device = Device.objects.get(device_id=device_id)
        except Device.DoesNotExist:
            return JsonResponse({"error": "Unknown device"}, status=401)

        try:
            ts = int(timestamp) / 1000.0
        except ValueError:
            return JsonResponse({"error": "Invalid timestamp"}, status=400)

        now = time.time()
        if abs(now - ts) > self.MAX_SKEW_SECONDS:
            return JsonResponse({"error": "Timestamp skew too large"}, status=401)

        if UsedNonce.objects.filter(value=nonce, expires_at__gt=timezone.now()).exists():
            return JsonResponse({"error": "Nonce already used"}, status=401)

        body = request.body
        body_hash = hashlib.sha256(body).hexdigest()
        signing_string = f"{device_id}\n{timestamp}\n{nonce}\n{body_hash}"
        expected_sig = hmac.new(
            device.secret_key.encode(),
            signing_string.encode(),
            hashlib.sha256,
        ).hexdigest()

        if not hmac.compare_digest(expected_sig, signature):
            return JsonResponse({"error": "Invalid signature"}, status=401)

        UsedNonce.objects.create(value=nonce, expires_at=timezone.now() + timedelta(minutes=10))

        device.last_seen = timezone.now()
        device.save(update_fields=["last_seen"])

        request.device = device
        return self.get_response(request)
