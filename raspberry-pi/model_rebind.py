"""Archive the installed Sky Eye runtime data before an explicit reassignment."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from datetime import datetime, timezone
import uuid


JOURNAL = 'profile-reset.json'
MODEL_PATHS = ('logs', 'output', 'state/cry-personal', 'input/cry_commands', 'input/cry_receipts')
BRIDGE_PATHS = ('bridge-cursor.json', 'bridge-commands.json', 'bridge-errors.json', 'bridge-binding.json')
UNITS = ('sky-eye', 'sky-eye-objects', 'sky-eye-cry', 'sky-eye-export')


def local_path(root, relative):
    """Only move exact local paths, never follow a symlink into another dataset."""
    relative = Path(relative)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Invalid model data path.')
    path = root
    for part in relative.parts:
        path = path / part
        if path.is_symlink() or path.resolve() != path:
            raise ValueError('Profile reset does not support linked data directories: ' + str(path))
    return path


def unit_info(unit):
    result = subprocess.run(['systemctl', '--user', 'show', unit,
        '--property=LoadState', '--property=ActiveState', '--property=MainPID',
        '--property=WorkingDirectory', '--property=ExecStart', '--property=DropInPaths'],
        check=True, capture_output=True, text=True)
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def stop_model(root):
    if sys.platform != 'linux':
        raise ValueError('Fresh-profile setup must run on the Raspberry Pi Linux user session.')
    expected = {
        'sky-eye': ['/bin/bash', str(root / 'deploy/pi/edge.sh'), 'run'],
        'sky-eye-objects': ['/bin/bash', str(root / 'deploy/pi/edge.sh'), 'run', '--config', 'deploy/pi/config.objects.json'],
        'sky-eye-cry': ['/bin/bash', str(root / 'deploy/pi/cry-edge.sh'), 'live'],
        'sky-eye-export': [str(root / '.venv/bin/python'), '-m', 'sky_edge.export'],
    }
    loaded, camera = [], 'sky-eye'
    for unit in UNITS:
        info = unit_info(unit)
        if info.get('LoadState') == 'not-found' and unit == 'sky-eye-objects':
            continue
        command = re.search(r'argv\[\]=(.*?) ;', info.get('ExecStart', ''))
        if (info.get('LoadState') != 'loaded' or Path(info.get('WorkingDirectory', '')).resolve() != root
                or info.get('DropInPaths') or not command or shlex.split(command.group(1)) != expected[unit]):
            raise ValueError('Fresh-profile setup requires the standard Sky Eye service paths. Check ' + unit)
        loaded.append(unit)
        if unit == 'sky-eye-objects' and info.get('ActiveState') == 'active':
            camera = unit
    subprocess.run(['systemctl', '--user', 'stop'] + loaded, check=True)
    for unit in loaded:
        info = unit_info(unit)
        if info.get('ActiveState') not in ('inactive', 'failed') or info.get('MainPID') != '0':
            raise ValueError('Model service is still running: ' + unit)
    return camera


def check_layout(root):
    if not (root / 'start-all.sh').is_file():
        raise ValueError('Cannot find the installed Sky Eye model launcher.')
    config = json.loads((root / 'deploy/pi/cry.json').read_text(encoding='utf-8'))
    personal = config.get('personalization', {})
    if (config.get('status_path', 'logs/cry/status.json') != 'logs/cry/status.json'
            or personal.get('state_path', 'state/cry-personal/profile.json') != 'state/cry-personal/profile.json'
            or personal.get('command_dir', 'input/cry_commands') != 'input/cry_commands'):
        raise ValueError('Custom model data paths need a separate profile migration; no files were moved.')
    for relative in MODEL_PATHS + ('aicoo-backups',):
        local_path(root, relative)


def fresh_profile(agent, root):
    """Caller holds AgentLock and has authenticated the newly approved device.

    Each rename stays on the same filesystem. An unfinished journal blocks all
    uploads, and the same command can resume after interruption without erasing
    keys, models, configuration or the archived child records.
    """
    root = Path(root).expanduser().resolve()
    device = agent.credential['deviceId']
    journal = agent.read(JOURNAL)
    pending = journal and journal.get('phase') != 'complete'
    binding = agent.read('bridge-binding.json')
    if not pending:
        if binding and binding.get('deviceId') == device and binding.get('root') == str(root):
            print('This connection already has its model profile. No data was reset.', flush=True)
            return None
        if not binding or binding.get('root') != str(root):
            raise ValueError('Fresh-profile setup requires the previous binding for this model folder.')
    elif journal.get('root') != str(root) or journal.get('deviceId') != device:
        raise ValueError('An unfinished profile reset belongs to another binding. Keep its backup and use the original reset settings.')
    check_layout(root)
    for name in BRIDGE_PATHS:
        local_path(agent.state_dir.resolve(), name)
    # This also stops the exporter, which would otherwise backfill old logs.
    camera = stop_model(root)
    if not pending:
        archive_name = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8]
        journal = {'phase': 'archiving', 'root': str(root), 'deviceId': device,
                   'previousBinding': binding, 'archive': archive_name, 'camera': camera}
        agent.write(JOURNAL, journal)
    if not re.fullmatch(r'\d{8}T\d{6}Z-[a-f0-9]{8}', journal.get('archive', '')):
        raise ValueError('Invalid profile backup path.')
    archive = local_path(root, 'aicoo-backups/' + journal['archive'])
    archive.parent.mkdir(mode=0o700, exist_ok=True)
    archive.parent.chmod(0o700)
    archive.mkdir(mode=0o700, exist_ok=True)
    archive.chmod(0o700)
    paths = [(root, name, 'model/' + name) for name in MODEL_PATHS]
    paths += [(agent.state_dir.resolve(), name, 'bridge/' + name) for name in BRIDGE_PATHS]
    if journal['phase'] != 'files_archived':
        for source_root, name, target in paths:
            source = local_path(source_root, name)
            destination = local_path(archive, target)
            if source.exists():
                if destination.exists():
                    raise ValueError('Data was recreated during an interrupted reset. Keep the backup and stop any manually started model before continuing.')
                destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                os.rename(source, destination)
        journal['phase'] = 'files_archived'
        agent.write(JOURNAL, journal)
    # Only the empty model runtime may now be associated with the new device.
    # A crash before completing the journal remains fail-closed in Bridge.bind.
    agent.write('bridge-binding.json', {'deviceId': device, 'root': str(root)})
    journal['phase'] = 'complete'
    agent.write(JOURNAL, journal)
    print('Previous model records were backed up to: ' + str(archive), flush=True)
    print('Starting with an empty personal profile. Device keys and model files are unchanged.', flush=True)
    return journal['camera']
