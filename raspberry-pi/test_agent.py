import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from aicoo_device import Agent, ApiError, NoRedirect, parse_time
from urllib.error import URLError


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

    def test_local_unlink_signature_covers_the_original_device_id(self):
        self.agent.load_key(create=True); target = str(uuid.uuid4())
        challenge = {'challengeId': str(uuid.uuid4()), 'nonce': 'fixture'}
        with patch.object(self.agent, 'request', return_value=challenge) as request:
            signed = self.agent.proof('unlink', target)
        self.assertEqual(request.call_args.args[1]['deviceId'], target)
        key = serialization.load_der_public_key(base64.b64decode(signed['publicKey']))
        key.verify(base64.b64decode(signed['signature']), ('aicoo-device-v1\nunlink\n%s\nfixture\n%s' % (challenge['challengeId'], target)).encode())
        with self.assertRaises(Exception):
            key.verify(base64.b64decode(signed['signature']), ('aicoo-device-v1\nunlink\n%s\nfixture\n%s' % (challenge['challengeId'], uuid.uuid4())).encode())

    def test_unlink_keeps_identity_and_profile_binding_and_removes_only_sessions(self):
        self.agent.load_key(create=True); key = (self.agent.state_dir / 'identity.pem').read_bytes()
        target = str(uuid.uuid4()); binding = {'deviceId': target, 'root': '/model'}
        self.agent.save_credential({'deviceId': target, 'accessToken': 'old-fixture'})
        self.agent.write('bridge-binding.json', binding); self.agent.write('pending.json', {'fixture': True})
        with patch.object(self.agent, 'proof', return_value={'signed': 'fixture'}) as proof, patch.object(self.agent, 'request', return_value={'unlinked': True, 'deviceId': target}) as request:
            self.agent.unlink()
        proof.assert_called_once_with('unlink', target)
        request.assert_called_once_with('/device/unlink', {'signed': 'fixture'})
        self.assertEqual((self.agent.state_dir / 'identity.pem').read_bytes(), key)
        self.assertEqual(self.agent.read('bridge-binding.json'), binding)
        for name in ('credential.json', 'pending.json', 'unlink-request.json'): self.assertIsNone(self.agent.read(name))

    def test_network_failure_keeps_unlink_target_and_blocks_data_and_pairing(self):
        self.agent.load_key(create=True); target = str(uuid.uuid4())
        self.agent.save_credential({'deviceId': target, 'accessToken': 'fixture'})
        with patch.object(self.agent, 'proof', side_effect=URLError('offline')):
            with self.assertRaises(URLError): self.agent.unlink()
        self.assertEqual(self.agent.read('unlink-request.json')['deviceId'], target)
        with patch.object(self.agent, '_send') as send:
            with self.assertRaisesRegex(ValueError, 'pending'): self.agent.request('/device/heartbeat', {}, device=True)
            with self.assertRaisesRegex(ValueError, 'pending'): self.agent.send_frame('fixture', b'jpeg')
            with self.assertRaisesRegex(ValueError, 'pending'): self.agent.pair()
        send.assert_not_called()
        restarted = Agent(self.agent.api_url, self.agent.state_dir)
        with patch.object(restarted, 'proof', return_value={}) as proof, patch.object(restarted, 'request', return_value={'unlinked': True, 'deviceId': target}):
            restarted.unlink()
        proof.assert_called_once_with('unlink', target)

    def test_unlink_without_saved_credential_recovers_an_unredeemed_approval(self):
        self.agent.load_key(create=True); target = str(uuid.uuid4())
        def authenticate(): self.agent.save_credential({'deviceId': target, 'accessToken': 'fixture'})
        with patch.object(self.agent, 'authenticate', side_effect=authenticate), patch.object(self.agent, 'proof', return_value={}), patch.object(self.agent, 'request', return_value={'unlinked': True, 'deviceId': target}):
            self.agent.unlink()
        self.assertIsNone(self.agent.read('unlink-request.json'))
        with patch.object(self.agent, 'authenticate', side_effect=ApiError(401, 'DEVICE_NOT_PAIRED')), patch.object(self.agent, 'request') as request:
            self.agent.unlink()
        request.assert_not_called()

    def test_mismatched_unlink_acknowledgement_does_not_resume_sending(self):
        self.agent.load_key(create=True); self.agent.save_credential({'deviceId': str(uuid.uuid4()), 'accessToken': 'fixture'})
        with patch.object(self.agent, 'proof', return_value={}), patch.object(self.agent, 'request', return_value={'unlinked': True, 'deviceId': str(uuid.uuid4())}):
            with self.assertRaises(ValueError): self.agent.unlink()
        self.assertIsNotNone(self.agent.read('unlink-request.json'))


if __name__ == "__main__":
    unittest.main()
