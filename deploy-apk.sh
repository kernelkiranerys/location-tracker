#!/usr/bin/env bash
# Build a signed release APK for distribution.
#
# Usage: ./deploy-apk.sh [version_code] [version_name]
#   ./deploy-apk.sh                # uses versionCode/versionName from build.gradle.kts
#   ./deploy-apk.sh 3 1.2          # overrides version
#
# Output: app/build/outputs/apk/release/app-release.apk
#         app/build/outputs/apk/release/version.json
# Copy both to the server's app_updates directory.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0") && pwd)"
APP_DIR="$SCRIPT_DIR/android_app"

# Read version from build.gradle.kts
VERSION_CODE=$(grep 'versionCode' "$APP_DIR/app/build.gradle.kts" | head -1 | grep -oE '[0-9]+' | head -1)
VERSION_NAME=$(grep 'versionName' "$APP_DIR/app/build.gradle.kts" | head -1 | grep -oE '"[^"]*"' | tr -d '"')

# Override if args provided
if [ -n "$1" ]; then VERSION_CODE="$1"; fi
if [ -n "$2" ]; then VERSION_NAME="$2"; fi

echo "=== Building release APK v$VERSION_NAME ($VERSION_CODE) ==="

# Build — auto-detect JAVA_HOME (NixOS stores JDKs in /nix/store)
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(ls -d /nix/store/*openjdk-headless-17* 2>/dev/null | head -1)
    if [ -z "$JAVA_HOME" ]; then
        JAVA_HOME=$(ls -d /nix/store/*openjdk-*-17* 2>/dev/null | head -1)
    fi
    if [ -z "$JAVA_HOME" ]; then
        echo "ERROR: JAVA_HOME not set and no OpenJDK 17 found in /nix/store."
        echo "       Install jdk17 or set JAVA_HOME manually."
        exit 1
    fi
fi
echo "Using JAVA_HOME=$JAVA_HOME"
"$APP_DIR/gradlew" -p "$APP_DIR" :app:assembleRelease --console=plain

APK="$APP_DIR/app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)

# Generate version.json alongside the APK
VERSION_FILE="$APP_DIR/app/build/outputs/apk/release/version.json"
cat > "$VERSION_FILE" << EOF
{
    "version_code": $VERSION_CODE,
    "version_name": "$VERSION_NAME",
    "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo ""
echo "=== Build complete ==="
echo "APK:  $APK ($APK_SIZE)"
echo "JSON: $VERSION_FILE"
echo ""
echo "Copy both files to the server's app_updates directory:"
echo "  app-release.apk  ->  latest.apk"
echo "  version.json      ->  version.json"
