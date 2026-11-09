#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT
if command -v javac >/dev/null 2>&1; then
    javac -d "$temp_dir" app/src/main/java/com/sunny/glassesbridge/YuvPlanes.java tests/YuvPlanesTest.java
else
    java -m jdk.compiler/com.sun.tools.javac.Main -d "$temp_dir" app/src/main/java/com/sunny/glassesbridge/YuvPlanes.java tests/YuvPlanesTest.java
fi
java -cp "$temp_dir" YuvPlanesTest
