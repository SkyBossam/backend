"""Verify Pi-side unlink against the local API using only a synthetic account."""
from pathlib import Path
import tempfile
import uuid
from unittest.mock import patch
from aicoo_device import Agent, ApiError
from smoke_local import BASE, request
from start_aicoo import main as launch


def main():
    account = request('/auth/signup', {'email': 'unlink-test-%s@example.test' % uuid.uuid4(), 'password': uuid.uuid4().hex})
    request('/auth/verify-email', {'token': account['verificationToken']})
    token, workspace = account['accessToken'], account['workspaceId']
    child = request('/children', {'displayName': 'Local unlink fixture', 'birthDate': '2026-01-01'}, token, workspace)
    with tempfile.TemporaryDirectory(prefix='aicoo-unlink-test-') as folder:
        root = Path(folder); agent = Agent(BASE, root / 'state', allow_http=True); agent.load_key(create=True)
        pending = agent.request('/pairing/start', agent.proof('pair'))
        device = request('/device-pairings/approve', {'code': pending['userCode'], 'childId': child['id'], 'name': 'Unlink test Pi'}, token, workspace)['deviceId']
        devices = [device]
        try:
            agent.save_credential(agent.request('/pairing/token', {'deviceCode': pending['deviceCode']}))
            old = Agent(BASE, agent.state_dir, allow_http=True)
            fingerprint = agent.fingerprint()
            argv = ['start_aicoo.py', '--unlink', '--state-dir', str(agent.state_dir),
                    '--config', str(root / 'launcher.json'), '--sky-eye-dir', str(root / 'no-model-needed')]
            # Only systemd lifecycle is mocked on Windows; the key proof and
            # revocation use the actual HTTP server and persistent local database.
            with patch('sys.argv', argv), patch('start_aicoo.background_service.prepare_install', return_value=True), patch('start_aicoo.background_service.resume_standby') as standby:
                assert launch() == 0
            standby.assert_called_once()
            try:
                old.request('/device/me', device=True)
                raise AssertionError('Old device credential was still accepted')
            except ApiError as error:
                assert error.status == 401
            assert request('/children/%s/devices' % child['id'], token=token, workspace=workspace)['items'] == []
            restored = Agent(BASE, agent.state_dir, allow_http=True); restored.load_key()
            assert restored.fingerprint() == fingerprint
            pending = restored.request('/pairing/start', restored.proof('pair'))
            new = request('/device-pairings/approve', {'code': pending['userCode'], 'childId': child['id'], 'name': 'New approval'}, token, workspace)['deviceId']
            devices.append(new); assert new != device
            restored.save_credential(restored.request('/pairing/token', {'deviceCode': pending['deviceCode']}))
            assert restored.request('/device/me', device=True)['deviceId'] == new
        finally:
            for device in devices: request('/devices/' + device, token=token, workspace=workspace, method='DELETE')
            request('/auth/logout', {}, token)
    print('PASS: Pi command -> signed local unlink -> revoked token -> preserved identity -> new pairing code and approval.')


if __name__ == '__main__': main()
