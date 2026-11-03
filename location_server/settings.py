from pathlib import Path
import os

BASE_DIR = Path(__file__).resolve().parent.parent


def _env(key, default=''):
    return os.environ.get(key, default)


def _env_bool(key, default=False):
    return os.environ.get(key, str(default)).lower() in ('1', 'true', 'yes', 'on')


def _env_int(key, default=0):
    try:
        return int(os.environ.get(key, str(default)))
    except (TypeError, ValueError):
        return default


def _env_list(key, default=''):
    raw = os.environ.get(key, default)
    return [x.strip() for x in raw.split(',') if x.strip()]


SECRET_KEY = _env('DJANGO_SECRET_KEY', 'dev-secret-key-change-me')
DEBUG = _env_bool('DJANGO_DEBUG', False)
ALLOWED_HOSTS = _env_list(
    'DJANGO_ALLOWED_HOSTS',
    'tracker.example.com,localhost,127.0.0.1',
)

if not DEBUG:
    SECURE_SSL_REDIRECT = _env_bool('SECURE_SSL_REDIRECT', True)
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_HSTS_SECONDS = int(_env('SECURE_HSTS_SECONDS', '31536000'))
    SECURE_HSTS_INCLUDE_SUBDOMAINS = True
    SECURE_HSTS_PRELOAD = True
    SECURE_CONTENT_TYPE_NOSNIFF = True
    SECURE_REFERRER_POLICY = 'same-origin'
    X_FRAME_OPTIONS = 'DENY'
    CSRF_TRUSTED_ORIGINS = _env_list(
        'DJANGO_CSRF_TRUSTED_ORIGINS',
        ','.join(
            f'https://{h}:8443' for h in ALLOWED_HOSTS
            if h not in ('localhost', '127.0.0.1', 'testserver')
        ),
    )

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "tracker",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
    "tracker.middleware.HMACAuthMiddleware",
]

ROOT_URLCONF = "location_server.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "tracker" / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    }
]

WSGI_APPLICATION = "location_server.wsgi.application"
ASGI_APPLICATION = "location_server.asgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

AUTH_PASSWORD_VALIDATORS = []

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = BASE_DIR / "staticfiles"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

GOOGLE_GEOLOCATION_API_KEY_FILE = _env(
    'GOOGLE_GEOLOCATION_API_KEY_FILE',
    str(BASE_DIR / "google_api_key"),
)

TRACKER_SERVER_HOST = _env('TRACKER_SERVER_HOST', 'tracker.example.com')
TRACKER_SERVER_PORT = int(_env('TRACKER_SERVER_PORT', '8443'))
TRACKER_CERT_PIN_HASH = _env('TRACKER_CERT_PIN_HASH', '')

TRACKER_WIFI_GPS_DISAGREEMENT_M = _env_int('TRACKER_WIFI_GPS_DISAGREEMENT_M', 1000)
TRACKER_WIFI_GPS_MAX_ACC_M = _env_int('TRACKER_WIFI_GPS_MAX_ACC_M', 200)

APK_DIR = os.environ.get("APK_DIR", "/opt/location-tracker/app_updates")

CVE_API_BASE = _env('CVE_API_BASE', 'http://127.0.0.1:5000')

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {
        "simple": {"format": "%(asctime)s %(levelname)s %(name)s: %(message)s"},
    },
    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "formatter": "simple",
        },
    },
    "loggers": {
        "tracker": {
            "handlers": ["console"],
            "level": "INFO",
            "propagate": False,
        },
    },
}
