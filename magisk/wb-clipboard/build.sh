#!/usr/bin/env bash
# 重新生成 clip.jar（需要 JDK 8+ 与 Android build-tools 的 d8）
# 用法：ANDROID_JAR=/path/to/android.jar BUILD_TOOLS=/path/to/build-tools/xx ./build.sh
set -e
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-28/android.jar}"
D8="${D8:-d8}"
rm -rf build && mkdir -p build
javac --release 8 -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d build src/com/mcxgjkh/clip/*.java
"$D8" --release --lib "$ANDROID_JAR" --output build build/com/mcxgjkh/clip/*.class
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/clip.jar', 'w', zipfile.ZIP_DEFLATED) as z:
    z.write('build/classes.dex', 'classes.dex')
PY
cp build/clip.jar ./clip.jar
echo "clip.jar updated"
