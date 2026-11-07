#!/usr/bin/env bash
# Builds a portable, dependency-free MTR4toMTR3.jar (Java 17 target).
set -e
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

rm -rf out build/MTR4toMTR3.jar
mkdir -p out build

echo "compiling -> out/"
"$JAVAC" -encoding UTF-8 --release 17 -Xlint:-options -d out $(find src -name '*.java')

echo "packing -> build/MTR4toMTR3.jar"
"$JAR" --create --file build/MTR4toMTR3.jar --main-class mtrconv.Main -C out .

ls -l build/MTR4toMTR3.jar
