import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from aicoo_device import Agent, ApiError, NoRedirect, parse_time


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.agent = Agent("https://api.example", self.temp.name)

    def test_identity_survives_restart_and_proof_is_domain_separated(self):
        self.agent.load_key(create=True)
        fingerprint = self.agent.fingerprint()
        second = Agent("https://api.example", self.temp.name)
        second.load_key()
        self.assertEqual(fingerprint, second.fingerprint())
        challenge = {"challengeId": "123", "nonce": "nonce", "message": "untrusted-server-message"}
        with patch.object(second, "request", return_value=challenge):
            proof = second.proof("authenticate")
        key = serialization.load_der_public_key(base64.b64decode(proof["publicKey"]))
        key.verify(base64.b64decode(proof["signature"]), b"aicoo-device-v1\nauthenticate\n123\nnonce")
        with self.assertRaises(Exception):
            key.verify(base64.b64decode(proof["signature"]), b"aicoo-device-v1\npair\n123\nnonce")

    def test_credentials_are_private_and_bound_to_one_server(self):
        self.agent.save_credential({"accessToken": "test-only"})
        self.assertEqual(self.agent.read("credential.json")["accessToken"], "test-only")
        if os.name != "nt":
            self.assertEqual((Path(self.temp.name) / "credential.json").stat().st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError):
            Agent("https://another.example", self.temp.name)
        with self.assertRaises(ValueError):
            Agent("http://api.example", self.temp.name)

    def test_missing_identity_never_silently_replaces_a_registered_device(self):
        with self.assertRaises(ValueError):
            self.agent.run(once=True)
        self.assertFalse((Path(self.temp.name) / "identity.pem").exists())

    def test_run_reports_unknown_analysis_and_stops_after_unlink(self):
        self.agent.load_key(create=True)
        with patch.object(self.agent, "authenticate"), patch.object(self.agent, "request", return_value={}) as call:
            self.agent.run(once=True)
        self.assertEqual(call.call_args.args[0], "/device/heartbeat")
        self.assertEqual(call.call_args.args[1]["status"], "unverifiable")
        self.assertNotIn("safety", call.call_args.args[1])
        with patch.object(self.agent, "authenticate", side_effect=ApiError(401, "DEVICE_NOT_PAIRED")):
            with self.assertRaises(ApiError):
                self.agent.run(once=True)

    def test_redirects_never_forward_credentials(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.example"))

    def test_java_nanosecond_timestamps_and_timezones_on_python39(self):
        base = datetime(2026, 10, 31, 21, 31, 3, tzinfo=timezone.utc).timestamp()
        for fraction in ('', '.5', '.51', '.513', '.5138', '.51380', '.513804', '.5138043', '.51380430', '.513804300'):
            with self.subTest(fraction=fraction):
                expected = base + (float(fraction[:7]) if fraction else 0)
                self.assertEqual(parse_time('2026-10-31T21:31:03' + fraction + 'Z'), expected)
                self.assertEqual(parse_time('2026-11-01T06:31:03' + fraction + '+09:00'), expected)
        for invalid in ('2026-10-31T21:31:03', '2026-02-30T21:31:03Z', '2026-10-31T21:31:03.1234567890Z'):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                parse_time(invalid)

    def test_pair_resumes_saved_nanosecond_expiry_and_redeems_approval(self):
        pending = {'userCode': 'TEST-CODE', 'deviceCode': 'private-test-value',
                   'verificationUriComplete': 'https://app.example/devices',
                   'expiresAt': '2026-10-31T21:31:03.513804300+00:00'}
        self.agent.write('pending.json', pending)
        credential = {'deviceId': 'new-device', 'accessToken': 'fixture'}
        with patch('aicoo_device.time.time', return_value=parse_time('2026-10-31T21:30:00Z')), patch.object(self.agent, 'request', return_value=credential) as request, patch.dict('sys.modules', {'qrcode': None}):
            self.agent.pair()
        request.assert_called_once_with('/pairing/token', {'deviceCode': 'private-test-value'})
        self.assertEqual(self.agent.credential, credential)
        self.assertFalse((self.agent.state_dir / 'pending.json').exists())


if __name__ == "__main__":
    unittest.main()
