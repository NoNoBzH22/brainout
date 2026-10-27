#!/bin/bash
# Assembles a portable BrainOut folder for Windows or Linux: a bundled Java runtime,
# the game and its data, and a launcher (BrainOut.bat / BrainOut.sh).
# The game writes its settings next to its data, so keep the folder somewhere writable.
#
# Prerequisites (from the repository root):
#   bin/client/brainout-desktop.jar and bin/client/packages, see macos/build_app.sh
#
# Usage: packaging/build_portable.sh [output_dir]   (default: build/portable)
# Run it on the system the package is for: the runtime comes from that system's JDK.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/portable}"
APP="$OUT/BrainOut"

JAR="$ROOT/bin/client/brainout-desktop.jar"
PACKAGES="$ROOT/bin/client/packages"

[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
[ -d "$PACKAGES" ] || { echo "Missing $PACKAGES" >&2; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/game"

cp "$JAR" "$APP/game/"
cp -R "$PACKAGES" "$APP/game/packages"

jlink \
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$APP/runtime"

JVM_OPTIONS="-Xmx2g --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        # the game reads and writes files relative to the working directory: run it from game\
        printf '%s\r\n' \
            '@echo off' \
            'cd /d "%~dp0game"' \
            "start \"\" \"%~dp0runtime\\bin\\javaw.exe\" $JVM_OPTIONS -jar brainout-desktop.jar --offline --unsafe %*" \
            > "$APP/BrainOut.bat"
        cp "$ROOT/data/assets/main/icons/icon.ico" "$APP/game/"
        ;;
    *)
        cat > "$APP/BrainOut.sh" <<EOF
#!/bin/sh
# the game reads and writes files relative to the working directory: run it from game/
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
cd "\$DIR/game" || exit 1
exec "\$DIR/runtime/bin/java" $JVM_OPTIONS -jar brainout-desktop.jar --offline --unsafe "\$@"
EOF
        chmod +x "$APP/BrainOut.sh"
        cp "$ROOT/data/assets/main/icons/icon-512.png" "$APP/game/icon.png"
        ;;
esac

echo "Built $APP"
