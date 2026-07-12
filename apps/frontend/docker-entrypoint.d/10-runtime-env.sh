#!/bin/sh
# Regenerates env.js at container start so the API URL is runtime configuration,
# not a build-time bake. Empty VITE_API_URL means same-origin (ingress path routing).
set -eu

cat > /usr/share/nginx/html/env.js <<EOF
window.__ENV = { VITE_API_URL: "${VITE_API_URL:-}" };
EOF
