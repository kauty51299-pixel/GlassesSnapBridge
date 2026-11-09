#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [ -z "${ANDROID_HOME:-}" ]; then
    echo 'Install Android Studio and Android SDK Platform 36 first, then retry.' >&2
    exit 1
fi
version=8.13
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/glasses-snap-bridge"
mkdir -p "$cache_dir"
gradle_bin="$cache_dir/gradle-$version/bin/gradle"
if [ ! -x "$gradle_bin" ]; then
    download_dir=$(mktemp -d)
    trap 'rm -rf "$download_dir"' EXIT
    curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$download_dir/gradle.zip"
    expected=$(curl --fail --location "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256")
    actual=$(shasum -a 256 "$download_dir/gradle.zip" | cut -d ' ' -f 1)
    [ "$actual" = "$expected" ] || { echo 'Gradle checksum mismatch.' >&2; exit 1; }
    unzip -q "$download_dir/gradle.zip" -d "$cache_dir"
fi
sh tests/run.sh
"$gradle_bin" --no-daemon assembleDebug lintDebug
echo 'APK: app/build/outputs/apk/debug/app-debug.apk'
