#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p out
find src/main/java src/test/java -name '*.java' > sources.txt
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -source 8 -target 8 -Xlint:all -d out @sources.txt
else
  java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -Xlint:all -d out @sources.txt
fi
mkdir -p dist
if command -v jar >/dev/null 2>&1; then
  jar cfe dist/minidb.jar ru.lab.minidb.Main -C out ru/lab/minidb
else
  java -m jdk.jartool/sun.tools.jar.Main cfe dist/minidb.jar ru.lab.minidb.Main -C out ru/lab/minidb
fi
