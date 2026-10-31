#!/usr/bin/env python3
"""Forward Sky Eye's file interface using the paired aicoo device identity (Python 3.9)."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
from urllib.error import URLError

from aicoo_device import Agent, ApiError, AgentLock, parse_time
from camera_forwarder import CameraForwarder


def number(value, low=None, high=None):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        return None
    return value if (low is None or value >= low) and (high is None or value <= high) else None


def timestamp(value):
    value = number(value, 1577836800, 253402300799)
    return datetime.fromtimestamp(value, timezone.utc).isoformat() if value is not None else None


def choice(value, allowed, default=None):
    return value if isinstance(value, str) and value in allowed else default


def obj(value):
    return value if isinstance(value, dict) else {}


def identifier(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        return None


def fresh(value, now, limit):
    return number(value) is not None and -5 <= now - value <= limit


def map_status(raw, now):
    if not isinstance(raw, dict) or raw.get('schema_version') != 'sky-eye.status.v1':
        raise ValueError('Unsupported status schema')
    root_fresh = fresh(raw.get('wall_time_unix'), now, 5) and raw.get('simulation') is not True
    video, audio = obj(raw.get('video')), obj(raw.get('audio'))
    v, a = obj(video.get('data')), obj(audio.get('data'))
    video_ok = root_fresh and video.get('available') is True and fresh(v.get('wall_time_unix'), now, 3) and not v.get('error')
    audio_ok = root_fresh and audio.get('available') is True and fresh(a.get('wall_time_unix'), now, 4) and a.get('running', True) is True
    posture, fall = obj(v.get('posture')), obj(v.get('fall'))
    video_ok = bool(video_ok and posture.get('valid') is True and v.get('posture_state') != 'unavailable')
    result = {
        'observedAt': timestamp(raw['wall_time_unix']) if root_fresh else timestamp(now),
        'video': {'available': video_ok, 'observedAt': timestamp(v.get('wall_time_unix')) if video_ok else None,
                  'posture': choice(posture.get('posture'), ('supine', 'side', 'prone')),
                  'airway': choice(posture.get('airway'), ('airway_visible', 'airway_covered')),
                  'state': choice(v.get('posture_state'), ('normal', 'caution', 'risk', 'unavailable'), 'unavailable'),
                  'bodyConfidence': number(obj(posture.get('body')).get('conf'), 0, 1),
                  'faceConfidence': number(obj(posture.get('face')).get('conf'), 0, 1),
                  'geometryValid': v.get('geometry_valid') is True and fall.get('observation_valid') is True,
                  'fallState': choice(fall.get('state'), ('monitoring', 'edge_approach', 'edge_departure_warning', 'unavailable'), 'unavailable'),
                  'fps': number(v.get('processing_fps'), 0, 1000)},
        'audio': {'available': bool(audio_ok), 'observedAt': timestamp(a.get('wall_time_unix')) if audio_ok else None,
                  'cryDetected': bool(audio_ok and a.get('cry_detected') is True),
                  'babyCryScore': number(a.get('baby_cry_score'), 0, 1), 'cryingScore': number(a.get('crying_score'), 0, 1)},
        'personalization': None,
    }
    personal = obj(raw.get('personalization'))
    if audio_ok and personal and personal.get('simulation') is False:
        counts = {key: personal.get(key) for key in ('n_feedback', 'required_feedback', 'cry_windows', 'required_cry_windows')}
        if all(type(value) is int and 0 <= value <= 1000000 for value in counts.values()):
            hints = []
            ready = personal.get('state') == 'ready' and counts['n_feedback'] >= max(10, counts['required_feedback']) and counts['cry_windows'] >= max(10, counts['required_cry_windows'])
            if ready:
                for h in personal.get('recommendations', [])[:2]:
                    h = obj(h)
                    if h.get('action') in ('feed', 'diaper', 'hold') and number(h.get('score'), 0, 1) is not None:
                        hints.append({'action': h['action'], 'score': h['score']})
            episodes = []
            for ep in personal.get('recent_episodes', [])[:5]:
                ep = obj(ep)
                uid, start, end = identifier(ep.get('episode_uid')), timestamp(ep.get('started_at')), timestamp(ep.get('ended_at'))
                if uid and start and end and ep['ended_at'] >= ep['started_at'] and ep['ended_at'] <= now + 5:
                    episodes.append({'episodeUid': uid, 'startedAt': start, 'endedAt': end, 'action': choice(ep.get('action'), ('feed', 'diaper', 'hold', 'unknown', 'self'))})
            result['personalization'] = {'simulation': False,
                'state': choice(personal.get('state'), ('insufficient_history', 'collecting_audio', 'ready', 'idle', 'low_confidence'), 'insufficient_history'),
                'nFeedback': counts['n_feedback'], 'requiredFeedback': max(10, counts['required_feedback']),
                'cryWindows': min(300, counts['cry_windows']), 'requiredCryWindows': min(300, max(10, counts['required_cry_windows'])),
                'soundOn': personal.get('sound_on') is True, 'recommendations': hints, 'recentEpisodes': episodes}
    return result


def unavailable(now):
    return map_status({'schema_version': 'sky-eye.status.v1', 'wall_time_unix': now}, now)


def map_event(raw):
    if not isinstance(raw, dict) or raw.get('schema_version') != 'sky-eye.event.v1':
        raise ValueError('Unsupported event schema')
    p = obj(raw.get('payload'))
    if raw.get('simulation') is True or p.get('simulation') is True or p.get('synthetic_feedback') is True:
        return None
    kind = raw.get('kind')
    # Preserve candidate semantics; these are observations, not verified diagnoses.
    kind = {'cry_candidate_started': 'cry_started', 'cry_candidate_ended': 'cry_ended'}.get(kind, kind)
    if kind not in ('risk_started', 'risk_ended', 'risk_interrupted', 'status_changed', 'posture_changed', 'cry_started', 'cry_ended', 'cry_episode_started', 'cry_episode_ended'):
        return None
    if not isinstance(raw.get('id'), str) or not re.fullmatch('[a-f0-9]{64}', raw['id']):
        raise ValueError('Invalid event identity')
    at = raw.get('wall_time_unix')
    if timestamp(at) is None:
        raise ValueError('Missing UNIX event time')
    source = str(raw.get('source', ''))
    uid = identifier(p.get('episode_uid'))
    episode = uid or str(raw.get('source_event_id') or raw['id'])
    episode_key = hashlib.sha256((source + '\0' + episode).encode()).hexdigest()
    # Video times are monotonic. Only a matching monotonic anchor permits conversion.
    anchor = number(p.get('ts', p.get('video_ts_s')))
    def converted(field):
        value = number(p.get(field))
        if value is None:
            return None
        return timestamp(value if kind.startswith('cry') else at + value - anchor) if kind.startswith('cry') or anchor is not None else None
    return {'id': raw['id'], 'episodeKey': episode_key, 'kind': kind,
            'riskType': 'cry' if kind.startswith('cry') else choice(p.get('risk_type'), ('prone', 'face_covered', 'edge_departure_warning')),
            'observedAt': timestamp(at), 'startedAt': converted('started_at'), 'endedAt': converted('ended_at'),
            'episodeUid': uid, 'endReason': choice(p.get('end_reason'), ('quiet', 'input_gap', 'segment_limit', 'service_stopped'), 'interrupted' if kind == 'cry_episode_ended' else None)}


class Bridge:
    def __init__(self, agent, root):
        self.agent, self.root = agent, Path(root).expanduser().resolve()
        self.output = self.root / 'output'
        self.checkpoint = agent.read('bridge-cursor.json') or {}
        self.commands = agent.read('bridge-commands.json') or {}

    def bind(self):
        self.agent.require_linked_local_state()
        reset = self.agent.read('profile-reset.json')
        if reset and reset.get('phase') != 'complete':
            raise ValueError('Profile reset is unfinished. Run start-aicoo.sh --fresh-profile to resume before sending data.')
        binding = self.agent.read('bridge-binding.json')
        device = self.agent.credential['deviceId']
        if binding and binding['deviceId'] != device and binding['root'] == str(self.root):
            raise ValueError('Child/device binding changed. Run start-aicoo.sh --fresh-profile to back up the previous records and start fresh, or use a separate fresh model deployment.')
        self.agent.write('bridge-binding.json', {'deviceId': device, 'root': str(self.root)})

    def status(self):
        now = time.time()
        try:
            path = self.output / 'status.json'
            if path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError('Status exceeds size limit')
            status = map_status(json.loads(path.read_text(encoding='utf-8')), now)
        except (OSError, ValueError, TypeError, KeyError):
            status = unavailable(now)
        try:
            self.agent.request('/device/sky-eye/status', status, device=True)
        except ApiError as error:
            if error.code != 'STALE_MODEL_STATUS':
                raise

    @staticmethod
    def anchor(file, offset):
        file.seek(max(0, offset - 128))
        return hashlib.sha256(file.read(min(128, offset))).hexdigest()

    def events(self, limit=100):
        path = self.output / 'events.jsonl'
        try:
            file = path.open('rb')
        except FileNotFoundError:
            return
        with file:
            stat = os.fstat(file.fileno())
            identity = [str(path), stat.st_dev, stat.st_ino, self.agent.credential['deviceId']]
            cursor = self.checkpoint
            offset = cursor.get('offset', 0)
            if cursor.get('identity') != identity or stat.st_size < offset or cursor.get('anchor') != self.anchor(file, offset):
                offset = 0
            for _ in range(limit):
                file.seek(offset)
                line = file.readline(262145)
                if len(line) > 262144:
                    raise ValueError('Event line exceeds 256 KiB; inspect the source file')
                if not line.endswith(b'\n'):
                    break
                next_offset = file.tell()
                try:
                    body = map_event(json.loads(line))
                    if body:
                        self.agent.request('/device/sky-eye/events', body, device=True)
                except (ValueError, TypeError, KeyError):
                    self.reject(offset, line, 'invalid_event')
                except ApiError as error:
                    if error.status not in (409, 422):
                        raise
                    self.reject(offset, line, error.code)
                # Advance only after a confirmed receipt (or an explicit diagnostic quarantine).
                offset = next_offset
                self.checkpoint = {'identity': identity, 'offset': offset, 'anchor': self.anchor(file, offset)}
                self.agent.write('bridge-cursor.json', self.checkpoint)

    def reject(self, offset, line, reason):
        # Keep bounded metadata; never copy raw model output or embeddings into diagnostics.
        errors = self.agent.read('bridge-errors.json') or []
        self.agent.write('bridge-errors.json', (errors + [{'offset': offset, 'hash': hashlib.sha256(line).hexdigest(), 'reason': reason}])[-100:])
        print('Skipped an invalid model event; see bridge-errors.json.', flush=True)

    def feedback(self):
        pending = self.agent.request('/device/sky-eye/commands', device=True)['items']
        device = self.agent.credential['deviceId']
        for cmd in pending:
            command = str(uuid.UUID(cmd['id']))
            if command not in self.commands:
                self.commands[command] = {**cmd, 'deviceId': device}
        self.agent.write('bridge-commands.json', self.commands)
        folder = self.root / 'input' / 'cry_commands'
        receipts = self.root / 'input' / 'cry_receipts'
        for command, cmd in list(self.commands.items()):
            if cmd['deviceId'] != device:
                del self.commands[command]
                continue
            name = uuid.UUID(command).hex + '.json'
            receipt = receipts / name
            if receipt.exists():
                content = json.loads(receipt.read_text(encoding='utf-8'))
                if content.get('command_id') != uuid.UUID(command).hex or type(content.get('ok')) is not bool:
                    raise ValueError('Invalid feedback receipt')
                self.agent.request('/device/sky-eye/commands/' + command + '/receipt', {'ok': content['ok']}, device=True)
                del self.commands[command]
            elif time.time() > parse_time(cmd['expiresAt']) + 30:
                self.agent.request('/device/sky-eye/commands/' + command + '/receipt', {'ok': False}, device=True)
                del self.commands[command]
            elif time.time() <= parse_time(cmd['expiresAt']):
                folder.mkdir(mode=0o700, parents=True, exist_ok=True)
                target = folder / name
                if not target.exists() and len(list(folder.glob('*.json'))) < 100:
                    body = {'kind': 'feedback', 'command_id': uuid.UUID(command).hex,
                            'episode_uid': str(uuid.UUID(cmd['episodeUid'])),
                            'action': choice(cmd['action'], ('feed', 'diaper', 'hold', 'unknown', 'self')),
                            'created_at': parse_time(cmd['createdAt'])}
                    fd, temporary = tempfile.mkstemp(prefix='.aicoo-', dir=folder)
                    try:
                        with os.fdopen(fd, 'w', encoding='utf-8') as out:
                            json.dump(body, out); out.flush(); os.fsync(out.fileno())
                        os.replace(temporary, target)
                    finally:
                        if os.path.exists(temporary):
                            os.unlink(temporary)
        self.agent.write('bridge-commands.json', self.commands)

    def run(self, once=False, preview_port=8765, camera=True):
        self.forwarder = CameraForwarder(self.agent, preview_port) if camera and not once else None
        try:
            self._run(once)
        finally:
            if self.forwarder:
                self.forwarder.stop()

    def _run(self, once=False):
        self.agent.load_key()
        authenticated, delay = False, 3
        while True:
            try:
                if not authenticated:
                    self.agent.authenticate()
                    self.bind()
                    authenticated = True
                    if self.forwarder:
                        self.forwarder.start()
                self.status()
                self.events()
                self.feedback()
                if once:
                    print('Sky Eye status, event batch and feedback receipts synchronized.')
                    return
                delay = 3
                time.sleep(2)
            except ApiError as error:
                if error.code == 'DEVICE_NOT_PAIRED':
                    raise
                if error.status == 401 and authenticated:
                    authenticated = False
                    continue
                if once or error.status not in (429, 500, 502, 503, 504):
                    raise
                print('Backend unavailable; retrying in %ss.' % delay, flush=True)
                time.sleep(delay); delay = min(60, delay * 2)
            except (URLError, TimeoutError, OSError):
                if once:
                    raise
                print('Network/file unavailable; retrying in %ss.' % delay, flush=True)
                time.sleep(delay); delay = min(60, delay * 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-url', default=os.environ.get('AICOO_API_URL'))
    parser.add_argument('--state-dir', default=os.environ.get('AICOO_STATE_DIR', '~/.local/share/aicoo'))
    parser.add_argument('--sky-eye-dir', default=os.environ.get('SKY_EYE_DIR', '~/sky-eye'))
    parser.add_argument('--allow-http', action='store_true')
    parser.add_argument('--once', action='store_true')
    parser.add_argument('--preview-port', type=int, default=8765)
    parser.add_argument('--no-camera', action='store_true', help='Send observations only, without preview frames')
    args = parser.parse_args()
    if not args.api_url:
        parser.error('--api-url or AICOO_API_URL is required')
    try:
        agent = Agent(args.api_url, args.state_dir, args.allow_http)
        with AgentLock(agent.state_dir):
            Bridge(agent, args.sky_eye_dir).run(args.once, args.preview_port, not args.no_camera)
    except KeyboardInterrupt:
        print('\nStopped. Cursor and device identity are saved.')
    except (ApiError, ValueError, OSError, URLError) as error:
        print('aicoo bridge: %s' % error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
