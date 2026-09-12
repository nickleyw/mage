#!/bin/sh
set -eu

if [ -z "${XMAGE_BRIDGE_TOKEN:-}" ]; then
  echo "XMAGE_BRIDGE_TOKEN must be set to a long random value." >&2
  exit 2
fi

exec java ${JAVA_OPTS:-} \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  -cp '/opt/xmage/app.jar:/opt/xmage/lib/*' \
  mage.webbridge.BridgeServer 0.0.0.0 "${PORT:-8080}"
