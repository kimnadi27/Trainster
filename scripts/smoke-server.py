"""Exercise real HTTP + SQLite lifecycle in an isolated temporary database.

Run with server/.venv/Scripts/python.exe scripts/smoke-server.py.
No schedule mocks and no persistent accounts/itineraries are created.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    base = f"http://127.0.0.1:{port}"

    def request(path, method="GET", body=None, token=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(base + urllib.parse.quote(path, safe="/:?&="), data=json.dumps(body).encode() if body is not None else None,
                                     method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        result = json.loads(raw) if raw else None
        assert status == expected, (path, status, expected, result)
        return result

    with tempfile.TemporaryDirectory(prefix="trainster-http-") as temp_dir:
        env = {**os.environ, "TRAINSTER_DATABASE": str(Path(temp_dir) / "test.sqlite3"),
               "YANDEX_RASP_API_KEY": "", "TRAINSTER_ENV_FILE": str(Path(temp_dir) / "absent.env")}
        process = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
                                    "--port", str(port), "--log-level", "warning"],
                                   cwd=ROOT / "server", env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            for _ in range(80):
                if process.poll() is not None:
                    raise RuntimeError(process.stdout.read().decode(errors="replace"))
                try:
                    request("/health")
                    break
                except urllib.error.URLError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Server startup timed out")

            owner = request("/api/v1/auth/register", "POST", {"username": "organizer", "password": secrets.token_urlsafe(20)}, expected=201)
            member = request("/api/v1/auth/register", "POST", {"username": "friend", "password": secrets.token_urlsafe(20)}, expected=201)
            trip = request("/api/v1/trips", "POST", {"title": "HTTP smoke check", "description": ""}, owner["token"], 201)
            trip_url = "/api/v1/trips/" + trip["id"]
            request("/api/v1/trips/join", "POST", {"code": trip["invite_code"]}, member["token"], 404)
            pause_id = str(uuid4())
            trip = request(trip_url, "PUT", {"title": trip["title"], "description": "", "expected_revision": 0,
                           "blocks": [{"id": pause_id, "kind": "pause", "title": "Meeting", "notes": "At the station"}]}, owner["token"])
            request(trip_url + "/publish", "POST", {"expected_revision": trip["draft_revision"]}, owner["token"])
            joined = request("/api/v1/trips/join", "POST", {"code": trip["invite_code"]}, member["token"])
            assert joined["version"] == 1
            edited = request(trip_url, "PUT", {"title": "Unpublished edit", "description": "", "blocks": joined["blocks"],
                             "expected_revision": trip["draft_revision"]}, owner["token"])
            assert request(trip_url, token=member["token"])["title"] == "HTTP smoke check"
            request(trip_url + "/state", "POST", {"status": "active", "current_block_id": pause_id}, member["token"], 403)
            request(trip_url + "/state", "POST", {"status": "active", "current_block_id": pause_id}, owner["token"])

            with ThreadPoolExecutor(max_workers=8) as executor:
                snapshots = list(executor.map(lambda _: request(trip_url, token=member["token"]), range(24)))
            assert all(value["status"] == "active" and value["current_block_id"] == pause_id for value in snapshots)
            request(trip_url + "/publish", "POST", {"expected_revision": edited["draft_revision"]}, owner["token"])
            assert request(trip_url, token=member["token"])["title"] == "Unpublished edit"
            assert request("/api/v1/config")["schedule_configured"] is False
            request("/api/v1/stations?q=Мытищи", token=owner["token"], expected=503)
            request("/api/v1/auth/logout", "POST", {}, member["token"], 204)
            request("/api/v1/auth/me", token=member["token"], expected=401)
            print("PASS: real HTTP/SQLite registration, access control, draft isolation, publication, active state, 24 concurrent reads, missing provider key, logout.")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    main()
