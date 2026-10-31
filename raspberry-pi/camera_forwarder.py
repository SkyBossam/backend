"""Relay the existing loopback preview; never opens or competes for /dev/video0."""
from datetime import datetime, timezone
import json
import math
import threading
import time
from urllib.error import URLError
from urllib.request import build_opener
from aicoo_device import ApiError, NoRedirect

MAX_FRAME = 524288


def frame_time(status, now):
    at, age = status.get('wall_time_unix'), status.get('preview_age_s')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in (at, age)):
        raise ValueError('Preview has no fresh timestamp')
    if not 0 <= age <= 2 or not -5 <= now - at <= 3:
        raise ValueError('Preview is stale')
    return datetime.fromtimestamp(at, timezone.utc).isoformat()


class CameraForwarder:
    def __init__(self, agent, port=8765):
        if not 1 <= port <= 65535:
            raise ValueError('Invalid local preview port')
        self.agent, self.origin = agent, 'http://127.0.0.1:%d' % port
        self.stopping = threading.Event()
        self.thread = None
        self.previous = None
        self.message = None

    def read(self, path, limit):
        # Device/user tokens are never sent to the preview server, even on redirects.
        with build_opener(NoRedirect()).open(self.origin + path, timeout=1) as response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise ValueError('Preview response exceeds size limit')
            return data

    def send_once(self):
        status = json.loads(self.read('/api/status', 524288))
        captured = frame_time(status, time.time())
        if captured == self.previous:
            return
        jpeg = self.read('/frame.jpg', MAX_FRAME)
        if not jpeg.startswith(b'\xff\xd8') or not jpeg.endswith(b'\xff\xd9'):
            raise ValueError('Invalid preview JPEG')
        # Recheck time after fetching so a slow/stalled response cannot resurrect a frame.
        frame_time(status, time.time())
        self.agent.send_frame(captured, jpeg)
        self.previous = captured

    def start(self):
        if self.thread is None:
            self.thread = threading.Thread(target=self.run, name='aicoo-camera', daemon=True)
            self.thread.start()

    def report(self, message):
        if message != self.message:
            print(message, flush=True); self.message = message

    def run(self):
        while not self.stopping.is_set():
            try:
                self.send_once()
                self.report('Camera preview connected (up to 2 fps, no audio).')
            except ApiError as error:
                self.report('Camera upload waiting for backend (%s).' % error.code)
            except (URLError, OSError, ValueError, TypeError, AttributeError):
                self.report('Camera waiting for fresh Sky Eye preview on 127.0.0.1.')
            self.stopping.wait(.5)

    def stop(self):
        self.stopping.set()
        if self.thread:
            self.thread.join(timeout=6)
