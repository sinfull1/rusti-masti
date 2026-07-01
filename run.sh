#!/usr/bin/env bash
# Launch the render service headless with a hard memory ceiling.
#
# The 500 MB budget is dominated by NATIVE (off-heap) memory: WebKit's page heap, Prism/Glass
# buffers, image rasters, and thread stacks. Java -Xmx does NOT bound any of that. So we keep the
# Java heap deliberately small and leave the rest of the 500 MB for native allocations, then let the
# OS/container be the real ceiling (cgroup limit / MaxRAMPercentage).
#
# Default headless path: Xvfb (a virtual X server) — reliable for WebView, no extra Java deps.
# Needs: apt-get install xvfb   (provides xvfb-run)
set -euo pipefail

JAR="target/image-0.1.jar"
[ -f "$JAR" ] || { echo "Build first: ./mvnw -q package -DskipTests"; exit 1; }

JAVA_OPTS=(
  -XX:MaxRAMPercentage=45
  -XX:+UseSerialGC
  -XX:MaxMetaspaceSize=96m
  -Xss512k
  -Djava.awt.headless=true
  -Dprism.order=sw
  -Dprism.text=t2k
  -Dprism.lcdtext=false
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED
)

exec xvfb-run -a -s "-screen 0 1280x1024x24" \
  java "${JAVA_OPTS[@]}" -jar "$JAR"

# --- Alternative: pure-Java Monocle (no X server), if you added the Gluon dep (see pom.xml) ---
# exec java "${JAVA_OPTS[@]}" \
#   -Dglass.platform=Monocle -Dmonocle.platform=Headless \
#   -jar "$JAR"