#!/usr/bin/env python3
"""aicoo pairing + authenticated heartbeat agent (Python 3.9+)."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, HTTPRedirectHandler, build_opener

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


class ApiError(Exception):
    def __init__(self, status, code):
        super().__init__(f"{code} (HTTP {status})")
        self.status, self.code = status, code


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Never forward a device credential to a redirected host.
        return None


class AgentLock:
    """Only one process may rotate credentials or publish this device's state."""
    def __init__(self, state_dir):
        self.path = Path(state_dir) / 'agent.lock'

    def __enter__(self):
        self.file = self.path.open('a+b')
        if self.file.tell() == 0:
            self.file.write(b'0'); self.file.flush()
        self.file.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise ValueError('Another aicoo agent is running. Stop it before pairing/status/bridge.') from None
        return self

    def __exit__(self, *unused):
        self.file.close()


class Agent:
    def __init__(self, api_url, state_dir, allow_http=False):
        parsed = urlparse(api_url)
        if parsed.scheme not in ("https", "http") or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("Use the backend origin, e.g. https://api.aicoo.example (without /api/v1).")
        if parsed.scheme != "https" and not allow_http:
            raise ValueError("HTTPS is required. For a trusted development network only, pass --allow-http.")
        self.api_url = api_url.rstrip("/")
        self.state_dir = Path(state_dir).expanduser()
        self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.state_dir.chmod(0o700)
        configured = self.read("server.json")
        if configured and configured["api_url"] != self.api_url:
            raise ValueError("This state directory belongs to a different server. Use a separate --state-dir.")
        if not configured:
            self.write("server.json", {"api_url": self.api_url})
        self.key = None
        self.credential = self.read("credential.json")

    def read(self, name):
        path = self.state_dir / name
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None

    def write_bytes(self, name, content):
        # Restrictive permissions from creation, then atomic replacement to survive power loss.
        fd, temporary = tempfile.mkstemp(prefix=".aicoo-", dir=self.state_dir)
        try:
            with os.fdopen(fd, "wb") as file:
                file.write(content)
                file.flush()
                os.fsync(file.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.state_dir / name)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def write(self, name, content):
        self.write_bytes(name, json.dumps(content, ensure_ascii=False).encode("utf-8"))

    def load_key(self, create=False):
        path = self.state_dir / "identity.pem"
        if path.exists():
            self.key = serialization.load_pem_private_key(path.read_bytes(), password=None)
            if not isinstance(self.key, Ed25519PrivateKey):
                raise ValueError("Invalid device identity key.")
        elif create:
            self.key = Ed25519PrivateKey.generate()
            self.write_bytes("identity.pem", self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        else:
            raise ValueError("No device identity. Run the pair command first.")

    def request(self, path, body=None, device=False):
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body).encode("utf-8")
        if device:
            self.require_linked_local_state()
            if not self.credential:
                raise ValueError("No credential. Pair or authenticate first.")
            headers["X-Device-Token"] = self.credential["accessToken"]
        request = Request(self.api_url + "/api/v1" + path, data=payload, headers=headers)
        return self._send(request)

    def send_frame(self, captured_at, jpeg):
        self.require_linked_local_state()
        if not self.credential:
            raise ValueError("Pair the device before streaming.")
        headers = {"Content-Type": "image/jpeg", "Accept": "application/json",
                   "X-Device-Token": self.credential["accessToken"], "X-Frame-Captured-At": captured_at}
        return self._send(Request(self.api_url + "/api/v1/device/camera/frame", data=jpeg, headers=headers), timeout=3)

    def _send(self, request, timeout=15):
        try:
            with build_opener(NoRedirect()).open(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                code = json.load(error).get("error", {}).get("code", "API_ERROR")
            except (ValueError, AttributeError):
                code = "API_ERROR"
            raise ApiError(error.code, code) from None

    def proof(self, purpose, device_id=None):
        public = base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode("ascii")
        body = {"publicKey": public, "purpose": purpose}
        if purpose == 'unlink':
            device_id = str(uuid.UUID(device_id))
            body['deviceId'] = device_id
        challenge = self.request("/pairing/challenges", body)
        message = f"aicoo-device-v1\n{purpose}\n{challenge['challengeId']}\n{challenge['nonce']}"
        if purpose == 'unlink': message += '\n' + device_id
        return {"publicKey": public, "challengeId": challenge["challengeId"], "nonce": challenge["nonce"], "signature": base64.b64encode(self.key.sign(message.encode("utf-8"))).decode("ascii")}

    def require_linked_local_state(self):
        if self.read('unlink-request.json'):
            raise ValueError('An unlink request is pending. Transmission and pairing are paused until the server confirms it.')

    def queue_unlink(self):
        self.load_key()  # Never replace or delete the persistent Pi identity.
        if not self.read('unlink-request.json'):
            previous = self.credential or self.read('bridge-binding.json') or {}
            target = previous.get('deviceId')
            self.write('unlink-request.json', {'deviceId': str(uuid.UUID(target)) if target else None})

    def unlink(self):
        self.queue_unlink()
        pending = self.read('unlink-request.json')
        target = pending['deviceId']
        if not target:
            # Handles an approval whose first credential response was lost.
            try:
                self.authenticate()
            except ApiError as error:
                if error.code != 'DEVICE_NOT_PAIRED': raise
            else:
                target = self.credential['deviceId']
                self.write('unlink-request.json', {'deviceId': target})
        if target:
            result = self.request('/device/unlink', self.proof('unlink', target))
            if result.get('unlinked') is not True or result.get('deviceId') != target:
                raise ValueError('The server did not confirm this device was unlinked. Transmission remains paused.')
        # Remove only obsolete session files after the server acknowledges revocation.
        # Keep identity, model records and bridge binding for the next explicit backup.
        for name in ('credential.json', 'pending.json'):
            (self.state_dir / name).unlink(missing_ok=True)
        self.credential = None
        self.write('unlinked.json', {'deviceId': target})
        (self.state_dir / 'unlink-request.json').unlink(missing_ok=True)
        print('Unlinked from the account. Camera and data transmission are stopped.', flush=True)
        print('To get a new pairing code: bash ~/backend/raspberry-pi/start-aicoo.sh --fresh-profile --install-service', flush=True)

    def save_credential(self, credential):
        self.write("credential.json", credential)
        self.credential = credential
        (self.state_dir / 'unlinked.json').unlink(missing_ok=True)

    def authenticate(self):
        self.save_credential(self.request("/device/authenticate", self.proof("authenticate")))

    def fingerprint(self):
        public = base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode("ascii")
        return hashlib.sha256(public.encode("utf-8")).hexdigest()[:12]

    def pair(self):
        self.require_linked_local_state()
        self.load_key(create=True)
        pending = self.read("pending.json")
        if not pending or parse_time(pending["expiresAt"]) <= time.time():
            pending = self.request("/pairing/start", self.proof("pair"))
            self.write("pending.json", pending)
        print(f"\nPairing code: {pending['userCode']}\nDevice fingerprint: {self.fingerprint()}\nOpen: {pending['verificationUriComplete']}\nSign in, select your child, and approve within 5 minutes.", flush=True)
        try:
            import qrcode
            qr = qrcode.QRCode(border=2)
            qr.add_data(pending["verificationUriComplete"])
            qr.make(fit=True)
            qr.print_ascii(invert=True)
        except ImportError:
            pass  # Manual code and link work without this optional dependency.
        while parse_time(pending["expiresAt"]) > time.time():
            try:
                result = self.request("/pairing/token", {"deviceCode": pending["deviceCode"]})
                if result.get("accessToken"):
                    self.save_credential(result)
                    (self.state_dir / "pending.json").unlink(missing_ok=True)
                    print(f"Paired! Device ID: {result['deviceId']}\nDevice identity is saved.", flush=True)
                    return
            except ApiError as error:
                if error.status != 429:
                    raise
            except (URLError, TimeoutError, OSError):
                print("Network unavailable; retrying approval status...", flush=True)
            time.sleep(max(3, pending.get("interval", 3)))
        raise ValueError("Code expired. Run pair again; if already approved, run status or run to recover.")

    def status(self):
        self.load_key()
        self.authenticate()
        result = self.request("/device/me", device=True)
        print(json.dumps(result, ensure_ascii=False, indent=2))

    def run(self, once=False):
        self.load_key()
        authenticated = False
        delay = 3
        while True:
            try:
                if not authenticated:
                    self.authenticate()
                    authenticated = True
                # Connectivity does not establish camera availability or the baby's safety.
                self.request("/device/heartbeat", {"observedAt": datetime.now(timezone.utc).isoformat(), "status": "unverifiable"}, device=True)
                if once:
                    print("Heartbeat accepted.")
                    return
                delay = 3
                time.sleep(10)
            except ApiError as error:
                if error.code == "DEVICE_NOT_PAIRED":
                    raise
                if error.status == 401 and authenticated:
                    authenticated = False
                    continue
                if error.status not in (429, 500, 502, 503, 504):
                    raise
                if once:
                    raise
                print(f"Server unavailable ({error.status}); retrying in {delay}s.", flush=True)
                time.sleep(delay)
                delay = min(60, delay * 2)
            except (URLError, TimeoutError, OSError):
                if once:
                    raise
                print(f"Network unavailable; retrying in {delay}s.", flush=True)
                time.sleep(delay)
                delay = min(60, delay * 2)


def parse_time(value):
    # Java Instant includes up to nine fractional digits. Python 3.9's
    # fromisoformat only accepts three or six; normalize to microseconds first.
    match = re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})', value)
    if not match:
        raise ValueError('Invalid server timestamp (an ISO date with a timezone is required).')
    base, fraction, zone = match.groups()
    normalized = base + '.' + (fraction or '').ljust(6, '0')[:6] + ('+00:00' if zone == 'Z' else zone)
    return datetime.strptime(normalized, '%Y-%m-%dT%H:%M:%S.%f%z').timestamp()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("pair", "status", "run", "unlink"))
    parser.add_argument("--api-url", default=os.environ.get("AICOO_API_URL"))
    parser.add_argument("--state-dir", default=os.environ.get("AICOO_STATE_DIR", "~/.local/share/aicoo"))
    parser.add_argument("--allow-http", action="store_true", help="Allow HTTP on a trusted local development network")
    parser.add_argument("--once", action="store_true", help="Send one heartbeat then exit (run command)")
    args = parser.parse_args()
    if not args.api_url:
        parser.error("--api-url or AICOO_API_URL is required")
    try:
        agent = Agent(args.api_url, args.state_dir, args.allow_http)
        with AgentLock(agent.state_dir):
            if args.command == "run":
                agent.run(args.once)
            else:
                getattr(agent, args.command)()
    except KeyboardInterrupt:
        print("\nStopped. Device identity is saved; restart the same command to resume.")
    except (ApiError, ValueError, OSError, URLError) as error:
        # Do not log response bodies, authorization headers, or credentials.
        print(f"aicoo: {error}", file=sys.stderr)
        if isinstance(error, ApiError) and error.code == "DEVICE_ALREADY_PAIRED":
            print("Already paired. Use run/status; the current owner must unlink before reassignment.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
