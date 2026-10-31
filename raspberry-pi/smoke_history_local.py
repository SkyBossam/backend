"""Opt-in local HTTP smoke: synthetic Pi cry -> durable history -> care note -> relogin."""
from datetime import datetime, timedelta, timezone
import tempfile
import uuid
from aicoo_device import Agent
from smoke_local import BASE, request


def main():
    email, password = 'history-test-%s@example.test' % uuid.uuid4(), uuid.uuid4().hex
    account = request('/auth/signup', {'email': email, 'password': password})
    request('/auth/verify-email', {'token': account['verificationToken']})
    token, workspace = account['accessToken'], account['workspaceId']
    child = request('/children', {'displayName': 'Care history test', 'birthDate': '2026-01-01'}, token, workspace)
    path = '/children/%s/care-history' % child['id']
    with tempfile.TemporaryDirectory(prefix='aicoo-history-test-') as folder:
        agent = Agent(BASE, folder, allow_http=True); agent.load_key(create=True)
        pending = agent.request('/pairing/start', agent.proof('pair'))
        device = request('/device-pairings/approve', {'code': pending['userCode'], 'childId': child['id'], 'name': 'History test Pi'}, token, workspace)['deviceId']
        try:
            agent.save_credential(agent.request('/pairing/token', {'deviceCode': pending['deviceCode']}))
            at = datetime.now(timezone.utc) - timedelta(hours=2)
            end = at + timedelta(seconds=74)
            event = {'id': 'e' * 64, 'episodeKey': 'history:cry', 'kind': 'cry_ended', 'riskType': 'cry', 'observedAt': end.isoformat(), 'startedAt': at.isoformat(), 'endedAt': end.isoformat()}
            agent.request('/device/sky-eye/events', event, device=True)
            agent.request('/device/sky-eye/events', event, device=True)
            cries = request(path + '?kind=cries', token=token, workspace=workspace)['items']
            assert len(cries) == 1 and cries[0]['durationSec'] == 74
            body = {'routines': [{'id': str(uuid.uuid4()), 'type': 'play', 'title': 'Play time', 'note': '', 'scheduledAt': at.isoformat(), 'endedAt': end.isoformat(), 'status': 'done'}],
                    'deletedRoutines': [], 'sleepRecords': [{'id': str(uuid.uuid4()), 'startedAt': (at - timedelta(hours=1)).isoformat(), 'endedAt': at.isoformat()}], 'deletedSleepRecords': [],
                    'feedback': [{'eventId': cries[0]['id'], 'actions': ['hug'], 'note': 'Synthetic care note', 'response': 'stopped'}]}
            saved = request(path, body, token, workspace, method='PUT')
            assert saved['routines'][0]['version'] == 1
            request('/auth/logout', {}, token)
            session = request('/auth/login', {'email': email, 'password': password}); token = session['accessToken']
            for kind in ('cries', 'routines', 'sleepRecords', 'feedback'):
                assert len(request(path + '?kind=' + kind, token=token, workspace=workspace)['items']) == 1
        finally:
            request('/devices/' + device, token=token, workspace=workspace, method='DELETE')
        assert len(request(path + '?kind=cries', token=token, workspace=workspace)['items']) == 1
        request('/auth/logout', {}, token)
    print('PASS: duplicate Pi cry -> DB history -> sleep/routine/care note -> relogin -> history retained after unlink.')


if __name__ == '__main__': main()
