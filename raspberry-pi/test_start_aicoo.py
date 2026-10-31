import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from aicoo_device import Agent, ApiError
from start_aicoo import origin, resolve_settings, save_config, ensure_paired, start_model, main


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.args = argparse.Namespace(api_url=None,state_dir=str(self.root / 'state'),sky_eye_dir=str(self.root / 'model'),preview_port=None)

    def test_reuses_already_paired_server_without_prompt_or_environment(self):
        agent = Agent('http://192.168.0.8:8080', self.args.state_dir, allow_http=True)
        with patch('builtins.input', side_effect=AssertionError('Should not prompt')):
            settings = resolve_settings(self.args, {}, {})
        self.assertEqual(settings['api_url'], agent.api_url)

    def test_address_normalization_and_different_server_does_not_steal_identity(self):
        self.assertEqual(origin('192.168.0.8'), 'http://192.168.0.8:8080')
        self.assertEqual(origin('https://api.example/api/v1/'), 'https://api.example')
        for url in ('http://user:password@host', 'https://host?token=secret', 'https://host/other'):
            with self.assertRaises(ValueError): origin(url)
        Agent('https://first.example', self.args.state_dir)
        self.args.api_url = 'https://different.example'
        with self.assertRaises(ValueError): resolve_settings(self.args, {}, {})

    def test_existing_identity_authenticates_without_pairing_again(self):
        agent = Agent('https://api.example', self.args.state_dir); agent.load_key(create=True)
        with patch.object(agent, 'authenticate') as authenticate, patch.object(agent, 'pair') as pair:
            ensure_paired(agent)
        authenticate.assert_called_once(); pair.assert_not_called()

    def test_first_pair_and_revoked_profile_are_different(self):
        agent = Agent('https://api.example', self.args.state_dir)
        with patch.object(agent, 'pair') as pair: ensure_paired(agent)
        pair.assert_called_once()
        agent.load_key(create=True); agent.write('bridge-binding.json', {'deviceId': 'old-child'})
        with patch.object(agent, 'authenticate', side_effect=ApiError(401, 'DEVICE_NOT_PAIRED')), patch.object(agent, 'pair') as pair:
            with self.assertRaises(ValueError): ensure_paired(agent)
        pair.assert_not_called()
        with patch.object(agent, 'authenticate', side_effect=ApiError(401, 'DEVICE_NOT_PAIRED')), patch.object(agent, 'pair') as pair:
            ensure_paired(agent, fresh=True)
        pair.assert_called_once()

    def test_config_is_saved_atomically_without_credentials(self):
        path = self.root / 'config/launcher.json'; settings = {'api_url': 'https://api.example', 'sky_eye_dir': 'model'}
        save_config(path, settings)
        self.assertEqual(json.loads(path.read_text()), settings)
        self.assertEqual(len(list(path.parent.iterdir())), 1)

    def test_start_preserves_running_model_variant(self):
        model = self.root / 'model'; model.mkdir(); (model / 'start-all.sh').write_text('fixture')
        with patch('start_aicoo.sys.platform', 'linux'), patch('start_aicoo.subprocess.run', return_value=Mock(returncode=0)) as run:
            start_model(model)
        calls = [call.args[0] for call in run.call_args_list]
        self.assertIn(['systemctl', '--user', 'start', 'sky-eye-objects', 'sky-eye-cry', 'sky-eye-export'], calls)
        self.assertFalse(any('restart' in call or 'stop' in call for call in calls))

    def test_one_command_continues_from_existing_pair_to_bridge(self):
        agent = Agent('http://127.0.0.1:8080', self.args.state_dir, allow_http=True); agent.load_key(create=True)
        config = self.root / 'launcher.json'
        argv = ['start_aicoo.py','--state-dir',self.args.state_dir,'--sky-eye-dir',self.args.sky_eye_dir,'--config',str(config),'--no-start-model','--once']
        with patch('sys.argv', argv), patch('start_aicoo.health'), patch.object(Agent,'authenticate'), patch.object(Agent,'pair') as pair, patch('start_aicoo.Bridge') as bridge:
            self.assertEqual(main(),0)
        pair.assert_not_called(); bridge.return_value.bind.assert_called_once()
        bridge.return_value.run.assert_called_once_with(True,8765,True)
        self.assertEqual(json.loads(config.read_text())['api_url'],agent.api_url)

    def test_reassignment_archives_before_binding_or_starting_transmission(self):
        order = []
        argv = ['start_aicoo.py', '--api-url', 'http://127.0.0.1:8080',
                '--state-dir', self.args.state_dir, '--sky-eye-dir', self.args.sky_eye_dir,
                '--config', str(self.root / 'launcher.json'), '--fresh-profile', '--once']
        model = Path(self.args.sky_eye_dir); model.mkdir(); (model / 'start-all.sh').write_text('fixture')
        with patch('sys.argv', argv), patch('start_aicoo.health'), patch('start_aicoo.ensure_paired', side_effect=lambda *a, **k: order.append('pair')), patch('start_aicoo.fresh_profile', side_effect=lambda *a: order.append('archive') or 'sky-eye-objects'), patch('start_aicoo.start_model', side_effect=lambda *a, **k: order.append('model')) as start, patch('start_aicoo.Bridge') as bridge:
            bridge.return_value.bind.side_effect = lambda: order.append('bind')
            bridge.return_value.run.side_effect = lambda *a: order.append('send')
            self.assertEqual(main(), 0)
        self.assertEqual(order, ['pair', 'archive', 'bind', 'model', 'send'])
        start.assert_called_once_with(str(model.resolve()), camera='sky-eye-objects')


if __name__ == '__main__': unittest.main()
