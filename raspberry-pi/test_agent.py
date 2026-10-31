import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from aicoo_device import Agent, ApiError, NoRedirect


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


if __name__ == "__main__":
    unittest.main()
