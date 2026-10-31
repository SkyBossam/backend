"""Explicit local HTTP test with synthetic files; does not access the physical Pi."""
import json
from pathlib import Path
import tempfile
import time
import uuid
from aicoo_device import Agent
from sky_eye_bridge import Bridge
from smoke_local import BASE, request
from test_sky_eye_bridge import status, event


def main():
    account = request('/auth/signup', {'email': 'bridge-test-%s@example.test' % uuid.uuid4(), 'password': uuid.uuid4().hex})
    request('/auth/verify-email', {'token': account['verificationToken']})
    token, workspace = account['accessToken'], account['workspaceId']
    child = request('/children', {'displayName': '파일 연동 검증용 아이', 'birthDate': '2026-01-01'}, token, workspace)
    with tempfile.TemporaryDirectory(prefix='aicoo-bridge-test-') as folder:
        root = Path(folder) / 'model'; (root / 'output').mkdir(parents=True)
        agent = Agent(BASE, Path(folder) / 'identity', allow_http=True); agent.load_key(create=True)
        pending = agent.request('/pairing/start', agent.proof('pair'))
        paired = request('/device-pairings/approve', {'code': pending['userCode'], 'childId': child['id'], 'name': '파일 검증용 파이'}, token, workspace)
        device = paired['deviceId']
        try:
            agent.save_credential(agent.request('/pairing/token', {'deviceCode': pending['deviceCode']}))
            now = time.time(); sample = status(now); episode = str(uuid.uuid4())
            sample['personalization']['recent_episodes'] = [{'episode_uid': episode, 'started_at': now - 20, 'ended_at': now - 10, 'action': None}]
            (root / 'output/status.json').write_text(json.dumps(sample), encoding='utf-8')
            line = json.dumps(event(now - 3600)) + '\n'
            (root / 'output/events.jsonl').write_text(line + line, encoding='utf-8')
            bridge = Bridge(agent, root); bridge.run(once=True)
            path = '/children/%s/sky-eye' % child['id']
            item = request(path, token=token, workspace=workspace)['items'][0]
            assert item['safety']['posture'] == 'stable' and item['safety']['objects'] == 'unknown'
            assert len(item['events']) == 1 and not item['events'][0]['recent']
            assert len(item['status']['personalization']['recommendations']) == 1
            cmd = request(path + '/feedback', {'deviceId': device, 'episodeUid': episode, 'action': 'hold'}, token, workspace)
            bridge.feedback()
            target = root / 'input/cry_commands' / (uuid.UUID(cmd['id']).hex + '.json')
            assert json.loads(target.read_text())['episode_uid'] == episode
            assert request(path, token=token, workspace=workspace)['items'][0]['commands'][0]['status'] == 'queued'
            receipt = root / 'input/cry_receipts' / target.name; receipt.parent.mkdir()
            receipt.write_text(json.dumps({'command_id': uuid.UUID(cmd['id']).hex, 'ok': True}))
            bridge.feedback()
            assert request(path, token=token, workspace=workspace)['items'][0]['commands'][0]['status'] == 'applied'
            sample['wall_time_unix'] = now - 60
            (root / 'output/status.json').write_text(json.dumps(sample), encoding='utf-8'); bridge.status()
            stale = request(path, token=token, workspace=workspace)['items'][0]
            assert stale['safety']['posture'] == 'unknown' and stale['status']['personalization'] is None
        finally:
            request('/devices/' + device, token=token, workspace=workspace, method='DELETE')
            request('/auth/logout', {}, token)
    print('PASS: file -> authenticated HTTP -> child snapshot, duplicate/history, feedback queue -> receipt, stale input and unlink.')


if __name__ == '__main__': main()
