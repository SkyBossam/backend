#!/usr/bin/env python3
"""One-command setup and foreground run, reusing the already approved device."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.parse import urlparse
from urllib.request import build_opener
from aicoo_device import Agent, AgentLock, ApiError, NoRedirect
from sky_eye_bridge import Bridge


def read_config(path):
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
    except FileNotFoundError:
        return {}
    if not isinstance(value, dict):
        raise ValueError('설정 파일이 올바른 JSON 객체가 아닙니다: %s' % path)
    return value


def origin(value):
    value = value.strip().rstrip('/')
    for suffix in ('/api/v1', '/api'):
        if value.endswith(suffix):
            value = value[:-len(suffix)]
            break
    if '://' not in value:
        value = 'http://' + value
        if ':' not in value[len('http://'):]:
            value += ':8080'
    parsed = urlparse(value)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise ValueError('PC 서버 주소를 입력해 주세요. 예: 192.168.0.10 또는 http://192.168.0.10:8080')
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError('서버 포트가 올바르지 않습니다.')
    return value


def resolve_settings(args, config, environ):
    state = Path(args.state_dir or environ.get('AICOO_STATE_DIR') or config.get('state_dir') or '~/.local/share/aicoo').expanduser().resolve()
    previous = read_config(state / 'server.json')
    address = args.api_url or environ.get('AICOO_API_URL') or previous.get('api_url') or config.get('api_url')
    if not address:
        if not sys.stdin.isatty():
            raise ValueError('최초 실행은 터미널에서 하거나 --api-url http://PC_IP:8080을 지정해 주세요.')
        address = input('PC 서버 주소를 입력해 주세요 (예: 192.168.0.10): ')
    address = origin(address)
    if previous.get('api_url') and origin(previous['api_url']) != address:
        raise ValueError('이 기기 키는 다른 서버에 연결되어 있습니다. 기존 서버 주소를 사용하거나 별도 --state-dir로 새 서버에 등록해 주세요.')
    root = Path(args.sky_eye_dir or environ.get('SKY_EYE_DIR') or config.get('sky_eye_dir') or '~/sky-eye').expanduser().resolve()
    port = args.preview_port or config.get('preview_port', 8765)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('미리보기 포트가 올바르지 않습니다.')
    return dict(api_url=address, state_dir=str(state), sky_eye_dir=str(root), preview_port=port)


def save_config(path, value):
    path = Path(path); path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.launcher-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            json.dump(value, out, ensure_ascii=False); out.flush(); os.fsync(out.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def health(address):
    with build_opener(NoRedirect()).open(address + '/actuator/health', timeout=3) as response:
        result = json.loads(response.read(16385))
        if result.get('status') != 'UP':
            raise ValueError('백엔드가 아직 준비되지 않았어요.')


def wait_for_server(address, once=False):
    announced = False
    while True:
        try:
            health(address)
            print('서버 연결 확인 완료: ' + address, flush=True)
            return
        except (OSError, URLError, ValueError, AttributeError):
            if once: raise ValueError('PC 백엔드에 연결하지 못했어요. 서버 실행과 네트워크를 확인해 주세요.') from None
            if not announced:
                print('PC 백엔드를 기다리고 있어요. PC에서 start-local.ps1 -Lan을 실행하고 같은 네트워크인지 확인해 주세요. (Ctrl+C로 중지)', flush=True)
                announced = True
            time.sleep(3)


def ensure_paired(agent):
    if (agent.state_dir / 'identity.pem').exists():
        agent.load_key()
        try:
            agent.authenticate()
            print('기존 아이쿠 연결을 복구했어요. 코드 재입력은 필요 없어요.', flush=True)
            return
        except ApiError as error:
            if error.code != 'DEVICE_NOT_PAIRED':
                raise
            # An unlink/reassignment must not silently transfer a child's old profile.
            if agent.read('bridge-binding.json'):
                raise ValueError('앱에서 연결 해제된 기기입니다. 새 아이에게 옮길 때는 새 모델 프로필을 준비한 뒤 pair로 명시적으로 등록해 주세요.') from None
    print('최초 연결: 아래 코드를 PC 아이쿠의 ‘아이쿠 연결하기’에 입력하고 승인해 주세요.', flush=True)
    agent.pair()


def start_model(root):
    root = Path(root)
    if not (root / 'start-all.sh').is_file():
        raise ValueError('모델 실행 파일을 찾지 못했어요: %s/start-all.sh (--sky-eye-dir로 설치 경로 지정)' % root)
    if sys.platform != 'linux':
        raise ValueError('모델 자동 실행은 파이의 Linux 사용자 세션에서 실행해 주세요.')
    # Start idempotently; do not restart a running model or switch its selected variant.
    active = subprocess.run(['systemctl', '--user', 'is-active', '--quiet', 'sky-eye-objects'], check=False).returncode == 0
    camera = 'sky-eye-objects' if active else 'sky-eye'
    units = [camera, 'sky-eye-cry', 'sky-eye-export']
    subprocess.run(['systemctl', '--user', 'start'] + units, check=True)
    for unit in units:
        subprocess.run(['systemctl', '--user', 'is-active', '--quiet', unit], check=True)
    print('카메라·울음·출력 서비스 실행 완료.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-url')
    parser.add_argument('--state-dir')
    parser.add_argument('--sky-eye-dir')
    parser.add_argument('--preview-port', type=int)
    parser.add_argument('--config', default='~/.config/aicoo/launcher.json')
    parser.add_argument('--no-start-model', action='store_true', help='이미 실행 중인 별도 모델을 사용')
    parser.add_argument('--no-camera', action='store_true', help='영상 없이 분석 데이터만 전송')
    parser.add_argument('--once', action='store_true', help='연결 및 데이터 전송을 한 번 점검')
    args = parser.parse_args()
    try:
        path = Path(args.config).expanduser()
        settings = resolve_settings(args, read_config(path), os.environ)
        # Fail on a missing model before waiting for approval or binding a new identity.
        if not args.no_start_model and not (Path(settings['sky_eye_dir']) / 'start-all.sh').is_file():
            raise ValueError('~/sky-eye/start-all.sh를 찾지 못했어요. --sky-eye-dir로 모델 설치 폴더를 지정해 주세요.')
        wait_for_server(settings['api_url'], args.once)
        agent = Agent(settings['api_url'], settings['state_dir'], allow_http=settings['api_url'].startswith('http://'))
        with AgentLock(agent.state_dir):
            save_config(path, settings)
            ensure_paired(agent)
            bridge = Bridge(agent, settings['sky_eye_dir'])
            bridge.bind()  # Verify child binding before starting the model or camera relay.
            if not args.no_start_model:
                start_model(settings['sky_eye_dir'])
            print('아이쿠 데이터·카메라 전송을 시작합니다. 이 터미널을 켜 두세요. 종료: Ctrl+C', flush=True)
            bridge.run(args.once, settings['preview_port'], not args.no_camera)
    except KeyboardInterrupt:
        print('\n전송을 중지했어요. 다음에도 같은 명령을 실행하면 연결을 이어갑니다.')
    except (ApiError, ValueError, OSError, URLError, subprocess.CalledProcessError) as error:
        print('아이쿠 시작 실패: %s' % error, file=sys.stderr)
        print('다른 aicoo 전송기/서비스가 실행 중이면 먼저 중지해 주세요. 키와 모델 프로필은 삭제하지 마세요.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
