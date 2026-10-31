"""Opt-in local test: synthetic device only; verifies cry grouping and caregiver history."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import tempfile
import uuid
from aicoo_device import Agent
from smoke_local import BASE, request


def main():
    username, password = "cry_test_" + uuid.uuid4().hex[:20], uuid.uuid4().hex
    account = request("/auth/signup", {"username": username, "password": password})
    token, workspace = account["accessToken"], account["workspaceId"]
    child = request("/children", {"displayName": "Cry follow-up fixture", "birthDate": "2026-01-01"}, token, workspace)["id"]
    path = f"/children/{child}/care-history"
    with tempfile.TemporaryDirectory(prefix="aicoo-cry-test-") as directory:
        agent = Agent(BASE, directory, allow_http=True)
        agent.load_key(create=True)
        pending = agent.request("/pairing/start", agent.proof("pair"))
        device = request("/device-pairings/approve", {"code": pending["userCode"], "childId": child, "name": "Cry test device"}, token, workspace)["deviceId"]
        try:
            agent.save_credential(agent.request("/pairing/token", {"deviceCode": pending["deviceCode"]}))
            def segment(index):
                started = datetime.now(timezone.utc) - timedelta(seconds=100-index*40)
                for kind, at in [("cry_started", started), ("cry_ended", started+timedelta(seconds=30))]:
                    body = {"id": sha256(f"{index}:{kind}".encode()).hexdigest(), "episodeKey": f"segment:{index}", "kind": kind, "observedAt": at.isoformat(), "startedAt": started.isoformat()}
                    if kind == "cry_ended": body["endedAt"] = at.isoformat()
                    agent.request("/device/sky-eye/events", body, device=True)
            segment(0)
            cries = request(path+"?kind=cries", token=token, workspace=workspace)["items"]
            assert len(cries) == 1
            note = {"eventId": cries[0]["id"], "actions": ["hug"], "note": "First care", "response": "continues"}
            body = {"routines": [], "deletedRoutines": [], "sleepRecords": [], "deletedSleepRecords": [], "feedback": [note]}
            saved = request(path, body, token, workspace, "PUT")["feedback"][0]
            segment(1)
            assert len(request(path+"?kind=cries", token=token, workspace=workspace)["items"]) == 1
            note.update(version=saved["version"], actions=["diaper"], response="stopped", note="Confirmed quiet")
            request(path, body, token, workspace, "PUT")
            login = request("/auth/login", {"username": username, "password": password})
            history = request(path+"?kind=feedback", token=login["accessToken"], workspace=workspace)["items"][0]
            assert history["version"] == 2 and history["response"] == "stopped"
            assert len(history["history"]) == 1 and history["history"][0]["response"] == "continues"
            assert history["history"][0]["actions"] == ["hug"]
            print("PASS: repeated cry -> one waiting record -> continued care -> stopped confirmation -> history restored after login.")
        finally:
            request(f"/devices/{device}", token=token, workspace=workspace, method="DELETE")


if __name__ == "__main__":
    main()
