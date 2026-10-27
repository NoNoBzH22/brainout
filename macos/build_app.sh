#!/bin/bash
# Assembles BrainOut.app from an already built client.
#
# Prerequisites (run from the repository root, on a Mac):
#   (cd scripts && python3 make_data.py client)       -> bin/client/packages
#   ./gradlew :desktop:dist -x :desktop:obfuscate      -> bin/client/brainout-desktop.jar
#
# Usage: macos/build_app.sh [output_dir]   (default: build/macos)
# Uses $JAVA_HOME's jlink to embed a Java runtime matching the build machine's
# architecture (arm64 on Apple Silicon).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/macos}"
APP="$OUT/BrainOut.app"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

JAR="$ROOT/bin/client/brainout-desktop.jar"
PACKAGES="$ROOT/bin/client/packages"

[ -f "$JAR" ] || { echo "Missing $JAR, run ./gradlew :desktop:dist first" >&2; exit 1; }
[ -d "$PACKAGES" ] || { echo "Missing $PACKAGES, run make_data.py client first" >&2; exit 1; }

VERSION="$(git -C "$ROOT" describe --tags --always 2>/dev/null || echo 1.0)"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/game"

sed "s/@VERSION@/$VERSION/g" "$ROOT/macos/Info.plist" > "$APP/Contents/Info.plist"
cp "$ROOT/macos/BrainOut" "$APP/Contents/MacOS/BrainOut"
chmod +x "$APP/Contents/MacOS/BrainOut"
cp "$ROOT/data/assets/main/icons/icon.icns" "$APP/Contents/Resources/icon.icns"

cp "$JAR" "$APP/Contents/Resources/game/"
cp -R "$PACKAGES" "$APP/Contents/Resources/game/packages"

"$JAVA_HOME/bin/jlink" \
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$APP/Contents/Resources/runtime"

# Ad-hoc signature: required for arm64 binaries to run; not notarized, so
# the first launch needs right-click > Open (or xattr -dr com.apple.quarantine).
codesign --force --deep --sign - "$APP"

echo "Built $APP"
