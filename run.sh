#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="$DIR/jdk/Contents/Home/bin/java"
KBFIX="$DIR/lib/keyboard_fix.dylib"
MICFIX="$DIR/lib/mic_fix.dylib"

# Build mic_fix if source exists but dylib doesn't
if [ ! -f "$MICFIX" ] && [ -f "$DIR/mic_fix.mm" ]; then
    echo "Building mic_fix.dylib..."
    clang -arch arm64 -std=c++17 -fobjc-arc -fPIC -dynamiclib \
        -framework Foundation -framework CoreAudio \
        -framework AudioToolbox -framework AVFoundation \
        -o "$MICFIX" "$DIR/mic_fix.mm"
    codesign --force --sign - "$MICFIX"
    xattr -rd com.apple.quarantine "$MICFIX" 2>/dev/null || true
    echo "✓ mic_fix.dylib built"
fi

# Build DYLD_INSERT_LIBRARIES from available dylibs
INJECT=""
[ -f "$KBFIX"  ] && INJECT="$KBFIX"
[ -f "$MICFIX" ] && INJECT="${INJECT:+$INJECT:}$MICFIX"

if [ -n "$INJECT" ]; then
    export DYLD_INSERT_LIBRARIES="$INJECT"
else
    echo "WARNING: No fix dylibs found"
fi

exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$DIR/lib" \
    -jar "$DIR/Citra-macOS.jar" "$@"
