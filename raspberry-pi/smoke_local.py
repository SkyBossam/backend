"""Opt-in integration test. Creates synthetic records on a LOCAL development server only."""
import json
from pathlib import Path
import tempfile
import uuid
from urllib.request import Request, urlopen
from aicoo_device import Agent, ApiError

BASE = "http://127.0.0.1:8080"


def request(path, body=None, token=None, workspace=None, method=None):
    headers = {"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4())}
    if token:
        headers["Authorization"] = "Bearer " + token
    if workspace:
        headers["X-Workspace-Id"] = workspace
    payload = json.dumps(body).encode() if body is not None else None
    with urlopen(Request(BASE + "/api/v1" + path, data=payload, headers=headers, method=method), timeout=15) as result:
        return json.load(result)


def main():
    account = request("/auth/signup", {"username": f"pi_smoke_{uuid.uuid4().hex[:20]}", "password": uuid.uuid4().hex})
    token, workspace = account["accessToken"], account["workspaceId"]
    child = request("/children", {"displayName": "연결 검증용 아이", "birthDate": "2026-01-01"}, token, workspace)
    # No raw credential is written to stdout or a repository file.
    with tempfile.TemporaryDirectory(prefix="aicoo-smoke-") as directory:
        agent = Agent(BASE, directory, allow_http=True)
        agent.load_key(create=True)
        pending = agent.request("/pairing/start", agent.proof("pair"))
        inspection = request("/device-pairings/inspect", {"code": pending["userCode"]}, token, workspace)
        assert inspection["fingerprint"] == agent.fingerprint()
        result = request("/device-pairings/approve", {"code": pending["userCode"], "childId": child["id"], "name": "연결 검증용 파이"}, token, workspace)
        device = result["deviceId"]
        try:
            agent.save_credential(agent.request("/pairing/token", {"deviceCode": pending["deviceCode"]}))
            assert agent.request("/device/me", device=True)["childId"] == child["id"]
            restarted = Agent(BASE, directory, allow_http=True)
            restarted.run(once=True)
            items = request(f"/children/{child['id']}/devices", token=token, workspace=workspace)["items"]
            assert len(items) == 1 and items[0]["connected"] is True
            assert items[0]["state"] == "unverifiable"
        finally:
            request(f"/devices/{device}", token=token, workspace=workspace, method="DELETE")
        try:
            restarted.request("/device/me", device=True)
            raise AssertionError("Revoked device token was accepted")
        except ApiError as error:
            assert error.status == 401
        request("/auth/logout", {}, token)
    print("PASS: pairing, matching fingerprint, approval, Pi token redemption, restart, heartbeat, unlink and revocation.")


if __name__ == "__main__":
    main()
