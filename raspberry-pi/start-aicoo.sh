#!/usr/bin/env bash
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
  echo '파이의 Linux 터미널에서 실행해 주세요.' >&2
  exit 1
fi
if [[ "$(id -u)" == 0 ]]; then
  echo 'sudo 없이 모델을 설치한 사용자(pi)로 실행해 주세요.' >&2
  exit 1
fi
if ! /usr/bin/python3 -c 'from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey' 2>/dev/null; then
  echo '아이쿠 전송에 필요한 Python 패키지를 설치합니다.'
  sudo apt-get update
  sudo apt-get install -y python3 python3-cryptography
fi
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec /usr/bin/python3 "$SCRIPT_DIR/start_aicoo.py" "$@"
