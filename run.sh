#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="$DIR/jdk/Contents/Home/bin/java"
KBFIX="$DIR/lib/keyboard_fix.dylib"
[ ! -f "$KBFIX" ] && echo "WARNING: keyboard_fix.dylib missing" && KBFIX=""
export DYLD_INSERT_LIBRARIES="$KBFIX"
exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$DIR/lib" \
    -jar "$DIR/Citra-macOS.jar" "$@"
