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
from model_rebind import fresh_profile
import background_service


def read_config(path):
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
    except FileNotFoundError:
        return {}
    if not isinstance(value, dict):
        raise ValueError('The configuration file must contain a JSON object: %s' % path)
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
        raise ValueError('Enter the PC server address, for example 192.168.0.10 or http://192.168.0.10:8080')
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError('The server port is invalid.')
    return value


def resolve_settings(args, config, environ):
    state = Path(args.state_dir or environ.get('AICOO_STATE_DIR') or config.get('state_dir') or '~/.local/share/aicoo').expanduser().resolve()
    previous = read_config(state / 'server.json')
    address = args.api_url or environ.get('AICOO_API_URL') or previous.get('api_url') or config.get('api_url')
    if not address:
        if not sys.stdin.isatty():
            raise ValueError('For first-time setup, run in an interactive terminal or pass --api-url http://PC_IP:8080.')
        address = input('Enter the PC server address (for example, 192.168.0.10): ')
    address = origin(address)
    if previous.get('api_url') and origin(previous['api_url']) != address:
        raise ValueError('This device key belongs to a different server. Use the original server address or a separate --state-dir to register with a new server.')
    root = Path(args.sky_eye_dir or environ.get('SKY_EYE_DIR') or config.get('sky_eye_dir') or '~/sky-eye').expanduser().resolve()
    port = args.preview_port or config.get('preview_port', 8765)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('The preview port is invalid.')
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
            raise ValueError('The backend is not ready yet.')


def wait_for_server(address, once=False):
    announced = False
    while True:
        try:
            health(address)
            print('Server connection confirmed: ' + address, flush=True)
            return
        except (OSError, URLError, ValueError, AttributeError):
            if once: raise ValueError('Cannot connect to the PC backend. Check that the server is running and the network is reachable.') from None
            if not announced:
                print('Waiting for the PC backend. Run start-local.ps1 -Lan on the PC and check the network connection. Press Ctrl+C to stop.', flush=True)
                announced = True
            time.sleep(3)


def ensure_paired(agent, fresh=False, unattended=False):
    if (agent.state_dir / 'identity.pem').exists():
        agent.load_key()
        try:
            agent.authenticate()
            print('Existing aicoo connection restored. No pairing code is needed.', flush=True)
            return
        except ApiError as error:
            if error.code != 'DEVICE_NOT_PAIRED':
                raise
            if unattended:
                raise ValueError('Device is unlinked. Waiting for a new approved connection; run start-aicoo.sh --fresh-profile --install-service to set it up') from None
            # An unlink/reassignment must not silently transfer a child's old profile.
            if agent.read('bridge-binding.json') and not fresh:
                raise ValueError('This device was unlinked. Run start-aicoo.sh --fresh-profile to approve a new connection, back up the previous model records, and start fresh.') from None
    if unattended:
        raise ValueError('Waiting for first-time pairing. Run start-aicoo.sh --install-service in a terminal')
    print('First-time setup: enter the code below on the device connection page in the aicoo PC app, then approve it.', flush=True)
    agent.pair()


def start_model(root, camera=None):
    root = Path(root)
    if not (root / 'start-all.sh').is_file():
        raise ValueError('Model launcher not found: %s/start-all.sh (set the installation path with --sky-eye-dir)' % root)
    if sys.platform != 'linux':
        raise ValueError('Automatic model startup requires a Linux user session on the Raspberry Pi.')
    # Start idempotently; do not restart a running model or switch its selected variant.
    if camera is None:
        active = subprocess.run(['systemctl', '--user', 'is-active', '--quiet', 'sky-eye-objects'], check=False).returncode == 0
        camera = 'sky-eye-objects' if active else 'sky-eye'
    if camera not in ('sky-eye', 'sky-eye-objects'):
        raise ValueError('Invalid model service variant.')
    units = [camera, 'sky-eye-cry', 'sky-eye-export']
    subprocess.run(['systemctl', '--user', 'start'] + units, check=True)
    for unit in units:
        subprocess.run(['systemctl', '--user', 'is-active', '--quiet', unit], check=True)
    print('Camera, cry detection and output services are running.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-url')
    parser.add_argument('--state-dir')
    parser.add_argument('--sky-eye-dir')
    parser.add_argument('--preview-port', type=int)
    parser.add_argument('--config', default='~/.config/aicoo/launcher.json')
    parser.add_argument('--fresh-profile', action='store_true', help='After reassignment, back up old model records locally and start with an empty personal profile')
    parser.add_argument('--no-start-model', action='store_true', help='Use a model that is already running separately')
    parser.add_argument('--no-camera', action='store_true', help='Send analysis data without camera preview frames')
    parser.add_argument('--once', action='store_true', help='Check the connection and send data once')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--install-service', action='store_true', help='Finish setup, enable startup after reboot and keep running in the background')
    mode.add_argument('--service', action='store_true', help='Unattended mode used by the installed standby service')
    args = parser.parse_args()
    if args.fresh_profile and args.no_start_model:
        parser.error('--fresh-profile must manage the model services; do not combine it with --no-start-model')
    if args.once and (args.install_service or args.service):
        parser.error('--once cannot be used with background service mode')
    if args.service and args.fresh_profile:
        parser.error('Profile changes require interactive setup, not --service')
    try:
        if args.install_service:
            background_service.prepare_install()
        path = Path(args.config).expanduser()
        settings = resolve_settings(args, read_config(path), os.environ)
        # Fail on a missing model before waiting for approval or binding a new identity.
        if not args.no_start_model and not (Path(settings['sky_eye_dir']) / 'start-all.sh').is_file():
            raise ValueError('Cannot find ~/sky-eye/start-all.sh. Set the model installation folder with --sky-eye-dir.')
        wait_for_server(settings['api_url'], args.once)
        agent = Agent(settings['api_url'], settings['state_dir'], allow_http=settings['api_url'].startswith('http://'))
        with AgentLock(agent.state_dir):
            save_config(path, settings)
            ensure_paired(agent, fresh=args.fresh_profile, unattended=args.service)
            camera = fresh_profile(agent, settings['sky_eye_dir']) if args.fresh_profile else None
            bridge = Bridge(agent, settings['sky_eye_dir'])
            bridge.bind()  # Verify child binding before starting the model or camera relay.
            if not args.no_start_model:
                start_model(settings['sky_eye_dir'], camera=camera)
            if not args.install_service:
                print('Starting aicoo data and camera transmission.' + ('' if args.service else ' Keep this terminal open. Press Ctrl+C to stop.'), flush=True)
                bridge.run(args.once, settings['preview_port'], not args.no_camera)
        if args.install_service:
            # The foreground process releases AgentLock before starting the daemon.
            background_service.install(Path(__file__), path, args.no_camera, args.no_start_model)
    except KeyboardInterrupt:
        print('\nTransmission stopped. Run the same command to reconnect next time.')
    except (ApiError, ValueError, OSError, URLError, subprocess.CalledProcessError) as error:
        if args.service:
            print('aicoo standby: %s. The background service will retry automatically.' % error, file=sys.stderr)
            return 1
        print('aicoo startup failed: %s' % error, file=sys.stderr)
        print('If another aicoo sender or service is running, stop it first. Do not delete device keys or model profiles.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
