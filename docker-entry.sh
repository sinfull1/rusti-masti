#!/usr/bin/env bash
# Start the render service under a virtual X server (Xvfb). WebView renders offscreen; no window is
# ever shown (we never call Stage.show()). Xvfb runs inside this same container/cgroup, so its memory
# counts against the 500 MB cap — deliberately, since that is the real deployment footprint.
#
# We start Xvfb directly (not via xvfb-run) with access control disabled (-ac): inside an isolated
# container there is nothing to protect against, and it avoids the Xauthority-cookie handshake that
# otherwise fails with "Authorization required, but no authorization protocol specified".
set -euo pipefail

DISP=":99"
Xvfb "$DISP" -screen 0 1280x2000x24 -nolisten tcp -ac >/tmp/xvfb.log 2>&1 &
export DISPLAY="$DISP"

# Wait for the X socket before launching the JVM (JavaFX opens the display eagerly at startup).
for _ in $(seq 1 100); do
    [ -S /tmp/.X11-unix/X99 ] && break
    sleep 0.1
done

exec java ${JAVA_OPTS} -jar /app/app.jar