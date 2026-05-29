#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
"$DIR/jdk/Contents/Home/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -cp "$DIR/Citra-macOS.jar" \
    AzaharSettings
