from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from background_service import install, prepare_install, service_text, UNIT


class BackgroundServiceTests(unittest.TestCase):
    def test_service_restarts_without_ever_repeating_profile_reset(self):
        content = service_text('/home/pi/aicoo checkout/start_aicoo.py', '/home/pi/config.json', no_camera=True)
        self.assertIn('Restart=always\nRestartSec=10', content)
        self.assertIn('"/home/pi/aicoo checkout/start_aicoo.py" --service', content)
        self.assertIn('--config "/home/pi/config.json" --no-camera', content)
        self.assertNotIn('--fresh-profile', content)
        self.assertNotIn('RestartPreventExitStatus', content)
        self.assertIn('%%', service_text('/home/pi/a%/run.py', '/config'))
        self.assertIn('$$', service_text('/home/pi/a$/run.py', '/config'))
        with self.assertRaises(ValueError): service_text('/path\nExecStart=bad', '/config')

    def test_existing_sender_stops_before_interactive_setup(self):
        with patch('background_service.sys.platform', 'linux'), patch('background_service.os.geteuid', return_value=1000, create=True), patch('background_service.subprocess.run', return_value=Mock(stdout='loaded\n')) as run:
            prepare_install()
        self.assertEqual(run.call_args.args[0], ['systemctl', '--user', 'stop', UNIT])
        with patch('background_service.sys.platform', 'linux'), patch('background_service.os.geteuid', return_value=0, create=True), patch('background_service.subprocess.run') as run:
            with self.assertRaises(ValueError): prepare_install()
        run.assert_not_called()

    def test_installer_enables_linger_boot_start_and_preserves_previous_unit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            path = root / '.config/systemd/user' / UNIT
            path.parent.mkdir(parents=True); path.write_text('previous unit')
            with patch('background_service.Path.home', return_value=root), patch('background_service.getpass.getuser', return_value='pi'), patch('background_service.subprocess.run', return_value=Mock(stdout='no\n')) as run:
                install(root / 'repo/start_aicoo.py', root / 'launcher.json')
            self.assertEqual(len(list(path.parent.glob('*.before-*'))), 1)
            self.assertEqual(next(path.parent.glob('*.before-*')).read_text(), 'previous unit')
            self.assertIn('--service', path.read_text())
            commands = [call.args[0] for call in run.call_args_list]
            self.assertIn(['sudo', 'loginctl', 'enable-linger', 'pi'], commands)
            self.assertIn(['systemctl', '--user', 'enable', '--now', UNIT], commands)
            self.assertEqual(commands[-1], ['systemctl', '--user', 'is-active', '--quiet', UNIT])
            with patch('background_service.Path.home', return_value=root), patch('background_service.subprocess.run', return_value=Mock(stdout='yes\n')) as run:
                install(root / 'repo/start_aicoo.py', root / 'launcher.json')
            self.assertFalse(any(call.args[0][0] == 'sudo' for call in run.call_args_list))
            self.assertEqual(len(list(path.parent.glob('*.before-*'))), 1)

    def test_linger_failure_is_reported_without_enabling_a_broken_service(self):
        with tempfile.TemporaryDirectory() as folder, patch('background_service.Path.home', return_value=Path(folder)), patch('background_service.subprocess.run', side_effect=[Mock(stdout='no\n'), subprocess.CalledProcessError(1, ['sudo'])]) as run:
            with self.assertRaises(subprocess.CalledProcessError): install('/launcher.py', '/config.json')
        self.assertFalse(any('enable' in call.args[0] for call in run.call_args_list))


if __name__ == '__main__': unittest.main()
