#!/usr/bin/env bash
# Builds every jar into dist/.
#   1.20.1 Forge runs Gradle on JDK 17, 1.21.1 Forge and NeoForge on JDK 21,
#   Fabric Loom 1.19 on JDK 25. ForgeGradle 7 also needs a JDK 25 for its Mavenizer tool.
# Override with JDK17=/path JDK21=/path JDK25=/path ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
PRISM_JAVA="$HOME/.local/share/PrismLauncher/java"
JDK17="${JDK17:-$PRISM_JAVA/java-runtime-gamma}"
JDK21="${JDK21:-$PRISM_JAVA/java-runtime-delta}"
JDK25="${JDK25:-$PRISM_JAVA/java-runtime-epsilon}"
VERSION=$(grep '^mod_version=' mod.properties | cut -d= -f2)
TOOLCHAINS="-Porg.gradle.java.installations.paths=$JDK17,$JDK21,$JDK25"

build() { # <mc> <loader> <jdk>
    local mc=$1 loader=$2 jdk=$3
    echo "Building $loader $mc"
    (cd "$mc/$loader" && JAVA_HOME="$jdk" ./gradlew build --console=plain -q "$TOOLCHAINS")
    cp "$mc/$loader/build/libs/claudecompanion-$loader-$mc-$VERSION.jar" dist/
}

rm -rf dist && mkdir -p dist
build 1.20.1 forge "$JDK17"
build 1.20.1 fabric "$JDK25"
build 1.21.1 forge "$JDK21"
build 1.21.1 neoforge "$JDK21"
build 1.21.1 fabric "$JDK25"
(cd dist && sha256sum *.jar > SHA256SUMS.txt)
ls -l dist
