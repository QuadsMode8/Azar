#!/bin/bash
# ============================================================
# build_and_install_fix.sh
# Run this from your Azar folder:  ./build_and_install_fix.sh
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/keyboard_fix.mm"
OUT="$SCRIPT_DIR/lib/keyboard_fix.dylib"

echo "=== Building corrected keyboard_fix.dylib ==="

# Compile the fixed source into a dylib
clang \
  -arch arm64 \
  -std=c++17 \
  -fobjc-arc \
  -fPIC \
  -dynamiclib \
  -framework Foundation \
  -framework AppKit \
  -o "$OUT" \
  "$SRC"

echo "✓ Compiled to $OUT"

# Remove quarantine so macOS doesn't block it
xattr -d com.apple.quarantine "$OUT" 2>/dev/null || true

# Ad-hoc sign so the JVM's library validation accepts it
codesign --force --sign - "$OUT"
echo "✓ Ad-hoc signed"

echo ""
echo "=== Done! Now run the emulator with: ==="
echo ""
echo "  DYLD_INSERT_LIBRARIES=./lib/keyboard_fix.dylib \\"
echo "  ./jdk/Contents/Home/bin/java \\"
echo "    -XstartOnFirstThread \\"
echo "    --enable-native-access=ALL-UNNAMED \\"
echo "    -Djava.library.path=./lib \\"
echo "    -jar Citra-macOS.jar \\"
echo '    /path/to/your/game.3ds'
echo ""
echo "You should see this in the output when the fix is active:"
echo "  [keyboard_fix] NSAlert thread-safety swizzle installed (init + runModal)"
