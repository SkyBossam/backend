import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from aicoo_device import Agent
from model_rebind import fresh_profile, stop_model, MODEL_PATHS, UNITS, JOURNAL
from sky_eye_bridge import Bridge


class FreshProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = (Path(self.temp.name) / 'sky-eye').resolve(); self.root.mkdir()
        self.agent = Agent('https://api.example', Path(self.temp.name) / 'agent')
        self.agent.load_key(create=True)
        self.agent.save_credential({'deviceId': 'new-device', 'accessToken': 'fixture'})
        self.agent.write('bridge-binding.json', {'deviceId': 'previous-device', 'root': str(self.root)})
        self.agent.write('bridge-cursor.json', {'offset': 42})
        self.agent.write('bridge-commands.json', {'old-command': {'deviceId': 'previous-device'}})
        (self.root / 'start-all.sh').write_text('fixture')
        (self.root / 'deploy/pi').mkdir(parents=True)
        (self.root / 'deploy/pi/cry.json').write_text('{}')
        (self.root / 'models').mkdir(); (self.root / 'models/weights.bin').write_bytes(b'model')
        for name in MODEL_PATHS:
            path = self.root / name; path.mkdir(parents=True)
            (path / 'old-child.json').write_text('old private record')

    def test_backup_preserves_keys_models_and_old_data_and_starts_empty(self):
        key = (self.agent.state_dir / 'identity.pem').read_bytes()
        with patch('model_rebind.stop_model', return_value='sky-eye-objects'):
            self.assertEqual(fresh_profile(self.agent, self.root), 'sky-eye-objects')
        journal = self.agent.read(JOURNAL)
        archive = self.root / 'aicoo-backups' / journal['archive']
        for name in MODEL_PATHS:
            self.assertEqual((archive / 'model' / name / 'old-child.json').read_text(), 'old private record')
            self.assertFalse((self.root / name).exists())
        self.assertEqual(json.loads((archive / 'bridge/bridge-binding.json').read_text())['deviceId'], 'previous-device')
        self.assertEqual((self.agent.state_dir / 'identity.pem').read_bytes(), key)
        self.assertEqual(self.agent.read('credential.json')['deviceId'], 'new-device')
        self.assertEqual((self.root / 'models/weights.bin').read_bytes(), b'model')
        self.assertEqual(journal['phase'], 'complete')
        bridge = Bridge(self.agent, self.root); bridge.bind()
        with patch.object(self.agent, 'request', return_value={'items': []}) as request:
            bridge.events(); request.assert_not_called()
            bridge.status()
            self.assertFalse(request.call_args.args[1]['audio']['available'])
        self.assertEqual(bridge.commands, {})
        # Repeating the user's command must not erase the new child's data.
        (self.root / 'logs').mkdir(); (self.root / 'logs/new.txt').write_text('new child')
        with patch('model_rebind.stop_model') as stop:
            self.assertIsNone(fresh_profile(self.agent, self.root))
        stop.assert_not_called()
        self.assertEqual((self.root / 'logs/new.txt').read_text(), 'new child')

    def test_interrupted_archive_blocks_uploads_and_resumes_without_data_loss(self):
        rename = os.rename
        def interrupted(source, destination):
            if Path(source) == self.root / 'output':
                raise OSError('simulated interruption')
            return rename(source, destination)
        with patch('model_rebind.stop_model', return_value='sky-eye'), patch('model_rebind.os.rename', side_effect=interrupted):
            with self.assertRaises(OSError): fresh_profile(self.agent, self.root)
        with self.assertRaisesRegex(ValueError, 'unfinished'):
            Bridge(self.agent, self.root).bind()
        archive_name = self.agent.read(JOURNAL)['archive']
        with patch('model_rebind.stop_model', return_value='sky-eye'):
            fresh_profile(self.agent, self.root)
        self.assertEqual(self.agent.read(JOURNAL)['archive'], archive_name)
        Bridge(self.agent, self.root).bind()
        for name in MODEL_PATHS:
            self.assertTrue((self.root / 'aicoo-backups' / archive_name / 'model' / name / 'old-child.json').exists())

    def test_interruption_after_binding_write_is_resumable(self):
        write = self.agent.write
        def interrupted(name, value):
            if name == JOURNAL and value['phase'] == 'complete':
                raise OSError('simulated power loss before final journal')
            write(name, value)
        with patch('model_rebind.stop_model', return_value='sky-eye'), patch.object(self.agent, 'write', side_effect=interrupted):
            with self.assertRaises(OSError): fresh_profile(self.agent, self.root)
        self.assertEqual(self.agent.read('bridge-binding.json')['deviceId'], 'new-device')
        with self.assertRaises(ValueError): Bridge(self.agent, self.root).bind()
        with patch('model_rebind.stop_model', return_value='sky-eye'):
            fresh_profile(self.agent, self.root)
        Bridge(self.agent, self.root).bind()

    def test_invalid_paths_or_failed_service_stop_never_move_records(self):
        config = self.root / 'deploy/pi/cry.json'
        config.write_text(json.dumps({'personalization': {'state_path': '/some/other/profile.json'}}))
        with patch('model_rebind.stop_model') as stop:
            with self.assertRaises(ValueError): fresh_profile(self.agent, self.root)
        stop.assert_not_called()
        config.write_text('{}')
        with patch('model_rebind.stop_model', side_effect=subprocess.CalledProcessError(1, ['systemctl'])):
            with self.assertRaises(subprocess.CalledProcessError): fresh_profile(self.agent, self.root)
        self.assertFalse((self.root / 'aicoo-backups').exists())
        for name in MODEL_PATHS: self.assertTrue((self.root / name / 'old-child.json').exists())

    def test_other_binding_cannot_resume_partial_reset(self):
        self.agent.write(JOURNAL, {'phase': 'archiving', 'root': str(self.root), 'deviceId': 'different-device'})
        with patch('model_rebind.stop_model') as stop:
            with self.assertRaises(ValueError): fresh_profile(self.agent, self.root)
        stop.assert_not_called()

    def test_linked_backup_directory_is_rejected_without_moving_files(self):
        outside = Path(self.temp.name) / 'outside'; outside.mkdir()
        try:
            (self.root / 'aicoo-backups').symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest('Creating symlinks is not permitted on this test machine')
        with patch('model_rebind.stop_model') as stop:
            with self.assertRaisesRegex(ValueError, 'linked'): fresh_profile(self.agent, self.root)
        stop.assert_not_called()
        self.assertEqual(list(outside.iterdir()), [])
        self.assertTrue((self.root / 'logs/old-child.json').exists())


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.root = Path('/home/pi/sky-eye').resolve()
        commands = {
            'sky-eye': ['/bin/bash', str(self.root / 'deploy/pi/edge.sh'), 'run'],
            'sky-eye-objects': ['/bin/bash', str(self.root / 'deploy/pi/edge.sh'), 'run', '--config', 'deploy/pi/config.objects.json'],
            'sky-eye-cry': ['/bin/bash', str(self.root / 'deploy/pi/cry-edge.sh'), 'live'],
            'sky-eye-export': [str(self.root / '.venv/bin/python'), '-m', 'sky_edge.export'],
        }
        self.info = {unit: {'LoadState': 'loaded', 'ActiveState': 'active', 'MainPID': '123',
            'WorkingDirectory': str(self.root), 'DropInPaths': '',
            'ExecStart': '{ path=/fixture ; argv[]=' + shlex.join(cmd) + ' ; ignore_errors=no ; }'} for unit, cmd in commands.items()}

    def test_stops_exporter_and_both_camera_variants_before_archiving(self):
        def info(unit):
            if run.called: return {**self.info[unit], 'ActiveState': 'inactive', 'MainPID': '0'}
            return self.info[unit]
        with patch('model_rebind.sys.platform', 'linux'), patch('model_rebind.unit_info', side_effect=info), patch('model_rebind.subprocess.run') as run:
            self.assertEqual(stop_model(self.root), 'sky-eye-objects')
        run.assert_called_once_with(['systemctl', '--user', 'stop'] + list(UNITS), check=True)

    def test_custom_unit_and_process_still_running_are_rejected(self):
        with patch('model_rebind.sys.platform', 'linux'), patch('model_rebind.unit_info', side_effect=lambda unit: self.info[unit]), patch('model_rebind.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'still running'): stop_model(self.root)
            self.info['sky-eye-cry']['ExecStart'] = '{ argv[]=/custom/script ; }'
            run.reset_mock()
            with self.assertRaisesRegex(ValueError, 'standard'): stop_model(self.root)
            run.assert_not_called()


if __name__ == '__main__': unittest.main()
