#!/bin/bash
cd "$(dirname "$0")"

JAVA_CMD="./jdk/Contents/Home/bin/java"
if [ ! -f "$JAVA_CMD" ]; then
    JAVA_CMD="java"
fi

if [ $# -eq 0 ]; then
    echo "Usage: ./run.sh /path/to/game.3ds"
    echo ""
    echo "Controls:"
    echo "  Z/X/C/V     = A/B/X/Y"
    echo "  Q/U         = L/R"
    echo "  E/O         = ZL/ZR"
    echo "  WASD        = Circle Pad"
    echo "  IJKL        = C-Stick"
    echo "  Arrow Keys  = D-Pad"
    echo "  Enter/Esc   = Start/Select"
    echo ""
    echo "Run ./settings.sh in another terminal tab to open settings."
    exit 0
fi

"$JAVA_CMD" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path=./lib \
    -jar Citra-macOS.jar "$@"
