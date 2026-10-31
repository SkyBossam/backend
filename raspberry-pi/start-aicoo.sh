#!/usr/bin/env bash
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
  echo 'Run this script in a Linux terminal on your Raspberry Pi.' >&2
  exit 1
fi
if [[ "$(id -u)" == 0 ]]; then
  echo 'Run as the user who installed the model (for example, pi), without sudo.' >&2
  exit 1
fi
if ! /usr/bin/python3 -c 'from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey' 2>/dev/null; then
  echo 'Installing the Python packages required by aicoo.'
  sudo apt-get update
  sudo apt-get install -y python3 python3-cryptography
fi
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec /usr/bin/python3 "$SCRIPT_DIR/start_aicoo.py" "$@"
