#!/usr/bin/env bash
# Builds every loader jar into dist/.
# Forge needs a JDK 17 and Fabric (Loom 1.19) needs a JDK 25 to run Gradle; both jars target Java 17.
# Override with JDK17=/path JDK25=/path ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
PRISM_JAVA="$HOME/.local/share/PrismLauncher/java"
JDK17="${JDK17:-$PRISM_JAVA/java-runtime-gamma}"
JDK25="${JDK25:-$PRISM_JAVA/java-runtime-epsilon}"
VERSION=$(grep '^mod_version=' mod.properties | cut -d= -f2)
MC=$(grep '^minecraft_version=' mod.properties | cut -d= -f2)

rm -rf dist && mkdir -p dist
(cd forge && JAVA_HOME="$JDK17" ./gradlew build --console=plain -q)
(cd fabric && JAVA_HOME="$JDK25" ./gradlew build --console=plain -q)
cp "forge/build/libs/claudecompanion-forge-$MC-$VERSION.jar" dist/
cp "fabric/build/libs/claudecompanion-fabric-$MC-$VERSION.jar" dist/
(cd dist && sha256sum *.jar > SHA256SUMS.txt)
ls -l dist
