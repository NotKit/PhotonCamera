#!/bin/sh
# Regenerate the android.os stubs and type-check them against a probe that
# mirrors GeckoView's real call sites. javadeps/ is scratch: Kotlin/Native has
# no JDK and os.kt needs java.io.File / java.io.FileDescriptor / java.util.Locale.
set -e
D=$(dirname "$0")
/home/nekit/UT/kn-toolchain/venv/bin/python "$D/os.py" --java-deps "$D/javadeps"
JAVA_OPTS="-Xmx4g" /home/nekit/UT/kn-toolchain/kotlin-native-prebuilt-linux-x86_64-2.4.10/bin/kotlinc-native \
  -target linux_x64 -p library -o /tmp/stub-os.klib \
  /home/nekit/UT/firefox-src-kn/mobile/android/geckoview-kn/src/stubs/os.kt \
  "$D/javadeps" "$D/probe-os.kt"
