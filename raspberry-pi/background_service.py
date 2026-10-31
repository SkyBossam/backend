"""Install the aicoo sender as this Pi user's persistent background service."""
import getpass
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid


UNIT = 'aicoo-sky-eye.service'


def prepare_install():
    if sys.platform != 'linux' or os.geteuid() == 0:
        raise ValueError('Install as the Pi model user in Linux, without sudo before start-aicoo.sh.')
    subprocess.run(['systemctl', '--user', 'daemon-reload'], check=True)
    result = subprocess.run(['systemctl', '--user', 'show', UNIT, '--property=LoadState', '--value'],
                            check=True, capture_output=True, text=True)
    state = result.stdout.strip()
    if state == 'loaded':
        # Release AgentLock before interactive pairing or profile migration.
        subprocess.run(['systemctl', '--user', 'stop', UNIT], check=True)
    elif state != 'not-found':
        raise ValueError('Cannot configure the aicoo user service: ' + state)
    return state == 'loaded'


def resume_standby():
    subprocess.run(['systemctl', '--user', 'start', UNIT], check=True)
    print('The aicoo standby service has restarted.', flush=True)


def service_text(launcher, config, no_camera=False, no_start_model=False):
    def quote(path):
        value = str(path)
        if any(char in value for char in ('\n', '\r', '\0')):
            raise ValueError('Invalid newline or control character in the service path.')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%').replace('$', '$$') + '"'
    command = '/usr/bin/python3 ' + quote(launcher) + ' --service --config ' + quote(config)
    if no_camera: command += ' --no-camera'
    if no_start_model: command += ' --no-start-model'
    return ('# Managed by aicoo start-aicoo.sh --install-service\n'
        '[Unit]\nDescription=aicoo connection standby and model sender\nAfter=network-online.target\n\n'
        '[Service]\nType=simple\nEnvironment=PYTHONUNBUFFERED=1\nExecStart=' + command + '\n'
        'Restart=always\nRestartSec=10\nTimeoutStopSec=20\nUMask=0077\nNoNewPrivileges=true\n\n'
        '[Install]\nWantedBy=default.target\n')


def install(launcher, config, no_camera=False, no_start_model=False):
    user = getpass.getuser()
    linger = subprocess.run(['loginctl', 'show-user', user, '--property=Linger', '--value'],
                            check=True, capture_output=True, text=True)
    if linger.stdout.strip() != 'yes':
        print('Enabling startup before login. sudo may ask for the Pi user password.', flush=True)
        subprocess.run(['sudo', 'loginctl', 'enable-linger', user], check=True)
    folder = Path.home() / '.config/systemd/user'
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / UNIT
    if path.is_symlink():
        raise ValueError('The aicoo service file is a link. Configure a regular user service file first.')
    content = service_text(Path(launcher).resolve(), Path(config).resolve(), no_camera, no_start_model)
    if path.exists() and path.read_text(encoding='utf-8') != content:
        backup = folder / (UNIT + '.before-' + uuid.uuid4().hex[:8])
        with backup.open('x', encoding='utf-8') as out:
            out.write(path.read_text(encoding='utf-8'))
        backup.chmod(0o600)
    fd, temporary = tempfile.mkstemp(prefix='.aicoo-service-', dir=folder)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as out:
            out.write(content); out.flush(); os.fsync(out.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    subprocess.run(['systemctl', '--user', 'daemon-reload'], check=True)
    subprocess.run(['systemctl', '--user', 'enable', '--now', UNIT], check=True)
    subprocess.run(['systemctl', '--user', 'is-active', '--quiet', UNIT], check=True)
    print('aicoo standby service is enabled. You can close this terminal.', flush=True)
    print('It starts after reboot and waits/reconnects when the PC server is unavailable.', flush=True)
    print('Status: systemctl --user status aicoo-sky-eye --no-pager', flush=True)

