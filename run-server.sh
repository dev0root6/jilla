#!/usr/bin/env bash
# Start the Jilla authority server (laptop = authority device).
#
#   ./run-server.sh            backend + mesh-sim (TCP dev mesh)
#   ./run-server.sh --tunnel   also expose the backend publicly via a Cloudflare quick tunnel
#
# Ctrl+C stops everything.
set -euo pipefail
cd "$(dirname "$0")"

# 1. Python env (first run only)
if [ ! -x backend/.venv/bin/uvicorn ]; then
  echo "Creating backend/.venv ..."
  python3 -m venv backend/.venv
  backend/.venv/bin/pip install -q -r backend/requirements.txt
fi

# 2. Dashboard token (protects decrypted alerts). Phones don't need it.
mkdir -p backend/keys
[ -s backend/keys/dashboard.token ] || python3 -c "import secrets;print(secrets.token_urlsafe(12))" > backend/keys/dashboard.token
chmod 600 backend/keys/dashboard.token
export JILLA_DASH_TOKEN="$(cat backend/keys/dashboard.token)"

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

# 3. mesh-sim hub for the TCP dev mesh (port 7777)
python3 devtools/mesh-sim/mesh_sim.py &
pids+=($!)

# 4. Backend (port 8000). Keys are created in backend/keys on first start.
backend/.venv/bin/uvicorn main:app --app-dir backend --host 0.0.0.0 --port 8000 &
pids+=($!)

sleep 2
lan_ip=$(ip -4 -o addr show scope global 2>/dev/null | awk '$2 !~ /^(docker|br-|tailscale)/ {split($4,a,"/"); print a[1]; exit}')
echo
echo "  Dashboard (this laptop): http://localhost:8000/?token=$JILLA_DASH_TOKEN"
echo "  Phone, same Wi-Fi:       server = ${lan_ip:-<laptop-ip>}"
echo "  Phone, USB:              adb reverse tcp:8000 tcp:8000 && adb reverse tcp:7777 tcp:7777 ; server = 127.0.0.1"
echo

# 5. Optional public tunnel
if [ "${1:-}" = "--tunnel" ]; then
  command -v cloudflared >/dev/null || { echo "cloudflared not installed"; exit 1; }
  echo "  Starting Cloudflare tunnel; use the https://...trycloudflare.com URL (no :8000) as the phone's server."
  echo "  Dashboard via tunnel: <that URL>/?token=$JILLA_DASH_TOKEN"
  cloudflared tunnel --url localhost:8000 2>&1 | grep --line-buffered -oE "https://[a-z0-9-]+\.trycloudflare\.com" &
  pids+=($!)
fi

wait
