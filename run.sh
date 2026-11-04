#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
# Set VRVNC_PASSWORD before launching when the VNC server requires a password.
export VRVNC_PASSWORD="${VRVNC_PASSWORD:-}"
./build.sh
exec .build/vrvncviewer
