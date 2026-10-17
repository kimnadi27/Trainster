from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.main import create_app
from app.security import token_hash, utcnow, verify_password


@pytest.fixture
def client(tmp_path):
    app = create_app(Settings(database=tmp_path / "test.sqlite3"))
    with TestClient(app) as client:
        yield client


def account(client, username="organizer"):
    response = client.post("/api/v1/auth/register", json={"username": username, "password": "correct-horse-123"})
    assert response.status_code == 201, response.text
    result = response.json()
    return result, {"Authorization": f"Bearer {result['token']}"}


def pause(title="Встреча"):
    return {"id": str(uuid4()), "kind": "pause", "title": title, "notes": "У вокзала",
            "starts_at": "2026-10-16T09:00:00+03:00", "ends_at": "2026-10-16T09:15:00+03:00"}


def create_trip(client, headers):
    response = client.post("/api/v1/trips", headers=headers, json={"title": "Рыбинск", "description": "Друзья"})
    assert response.status_code == 201
    return response.json()


def save(client, trip, headers, blocks, title="Рыбинск"):
    return client.put(f"/api/v1/trips/{trip['id']}", headers=headers,
                      json={"title": title, "description": "Друзья", "blocks": blocks,
                            "expected_revision": trip["draft_revision"]})


def publish(client, trip, headers):
    return client.post(f"/api/v1/trips/{trip['id']}/publish", headers=headers,
                       json={"expected_revision": trip["draft_revision"]})


def test_passwords_and_tokens_are_hashed_and_logout_revokes(client):
    auth, headers = account(client, "ORGANIZER")
    assert auth["user"]["username"] == "organizer"
    with client.app.state.database.connect() as db:
        user = db.execute("SELECT * FROM users").fetchone()
        session = db.execute("SELECT * FROM sessions").fetchone()
        assert user["password_hash"] != "correct-horse-123"
        assert verify_password("correct-horse-123", user["password_hash"])
        assert session["token_hash"] == token_hash(auth["token"])
        assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 1
    assert client.get("/api/v1/auth/me", headers=headers).json() == auth["user"]
    assert client.post("/api/v1/auth/logout", headers=headers).status_code == 204
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 401
    login = client.post("/api/v1/auth/login", json={"username": "Organizer", "password": "correct-horse-123"})
    assert login.status_code == 200
    assert login.json()["token"] != auth["token"]


def test_bad_password_unknown_user_and_duplicates(client):
    account(client)
    assert client.post("/api/v1/auth/register", json={"username": "Organizer", "password": "new-pass-123"}).status_code == 409
    for username in ("organizer", "missing"):
        response = client.post("/api/v1/auth/login", json={"username": username, "password": "incorrect123"})
        assert response.status_code == 401
        assert response.json() == {"detail": "Неверный логин или пароль."}
    response = client.post("/api/v1/auth/register", json={"username": "abc", "password": "short"})
    assert response.status_code == 422
    assert "short" not in response.text


def test_expired_session_is_rejected(client):
    auth, headers = account(client)
    with client.app.state.database.connect() as db:
        db.execute("UPDATE sessions SET expires_at=?", ((utcnow() - timedelta(seconds=1)).isoformat(),))
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 401


def test_auth_required_and_missing_provider_is_honest(client):
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/api/v1/config").json()["schedule_configured"] is False
    assert client.get("/api/v1/trips").status_code == 401
    assert client.get("/api/v1/stations?q=Ростов").status_code == 401
    _, headers = account(client)
    response = client.get("/api/v1/stations?q=Ростов", headers=headers)
    assert response.status_code == 503
    assert "YANDEX_RASP_API_KEY" in response.json()["detail"]


def test_members_see_only_published_snapshot_and_owner_draft(client):
    _, owner = account(client)
    _, member = account(client, "friend")
    trip = create_trip(client, owner)
    join = lambda: client.post("/api/v1/trips/join", headers=member, json={"code": trip["invite_code"].lower()})
    assert join().status_code == 404
    assert publish(client, trip, owner).status_code == 422
    original_block = pause()
    trip = save(client, trip, owner, [original_block]).json()
    published = publish(client, trip, owner).json()
    assert published["version"] == 1
    assert join().status_code == 200
    trip = save(client, trip, owner, [pause("Секретный черновик")], title="Новый черновик").json()
    member_trip = client.get(f"/api/v1/trips/{trip['id']}", headers=member).json()
    assert member_trip["title"] == "Рыбинск"
    assert member_trip["blocks"][0]["title"] == original_block["title"]
    assert member_trip["version"] == 1
    assert member_trip["published_blocks"] == []
    assert member_trip["has_unpublished_changes"] is False
    owner_trip = client.get(f"/api/v1/trips/{trip['id']}", headers=owner).json()
    assert owner_trip["title"] == "Новый черновик"
    assert owner_trip["published_blocks"][0]["title"] == original_block["title"]
    assert owner_trip["has_unpublished_changes"] is True
    assert publish(client, trip, owner).json()["version"] == 2
    assert client.get("/api/v1/trips", headers=member).json()[0]["title"] == "Новый черновик"


def test_access_control_hides_unknown_trips_and_blocks_member_edits(client):
    _, owner = account(client)
    _, member = account(client, "friend")
    _, outsider = account(client, "stranger")
    trip = create_trip(client, owner)
    trip = save(client, trip, owner, [pause()]).json()
    publish(client, trip, owner)
    url = f"/api/v1/trips/{trip['id']}"
    assert client.get(url, headers=outsider).status_code == 404
    assert client.get("/api/v1/trips", headers=outsider).json() == []
    client.post("/api/v1/trips/join", headers=member, json={"code": trip["invite_code"]})
    assert save(client, trip, member, [pause()]).status_code == 403
    assert publish(client, trip, member).status_code == 403
    assert client.post(url + "/state", headers=member, json={"status": "finished", "current_block_id": None}).status_code == 403


def test_stale_save_and_publish_rejected(client):
    _, owner = account(client)
    trip = create_trip(client, owner)
    latest = save(client, trip, owner, [pause()]).json()
    assert latest["draft_revision"] == 1
    assert save(client, trip, owner, [pause("Stale")]).status_code == 409
    assert publish(client, trip, owner).status_code == 409
    assert publish(client, latest, owner).status_code == 200


def test_concurrent_draft_writes_only_one_wins(client):
    _, owner = account(client)
    trip = create_trip(client, owner)
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda label: save(client, trip, owner, [pause(label)]), ["Один", "Два"]))
    assert sorted(response.status_code for response in responses) == [200, 409]
    assert client.get(f"/api/v1/trips/{trip['id']}", headers=owner).json()["draft_revision"] == 1


def test_active_state_references_published_plan_only(client):
    _, owner = account(client)
    trip = create_trip(client, owner)
    url = f"/api/v1/trips/{trip['id']}/state"
    original = pause()
    draft = pause("Новый этап")
    assert client.post(url, headers=owner, json={"status": "active", "current_block_id": original["id"]}).status_code == 409
    trip = save(client, trip, owner, [original]).json()
    publish(client, trip, owner)
    trip = save(client, trip, owner, [draft]).json()
    assert client.post(url, headers=owner, json={"status": "active", "current_block_id": draft["id"]}).status_code == 422
    assert client.post(url, headers=owner, json={"status": "active", "current_block_id": None}).status_code == 422
    active = client.post(url, headers=owner, json={"status": "active", "current_block_id": original["id"]})
    assert active.status_code == 200
    assert active.json()["current_block_id"] == original["id"]
    assert publish(client, trip, owner).status_code == 409
    assert client.post(url, headers=owner, json={"status": "planning", "current_block_id": None}).status_code == 200
    assert publish(client, trip, owner).status_code == 200


def test_block_ids_dates_and_unrouted_walk_are_validated(client):
    _, owner = account(client)
    trip = create_trip(client, owner)
    block = pause()
    assert save(client, trip, owner, [block, block]).status_code == 422
    block["starts_at"] = "2026-10-16T09:00:00"
    assert save(client, trip, owner, [block]).status_code == 422
    block = {"id": str(uuid4()), "kind": "walk", "title": "Город", "walk": {"points": [], "geometry": []}}
    trip = save(client, trip, owner, [block]).json()
    assert publish(client, trip, owner).status_code == 422


def test_state_and_publication_persist_after_restart(client):
    _, owner = account(client)
    trip = create_trip(client, owner)
    trip = save(client, trip, owner, [pause()]).json()
    publish(client, trip, owner)
    settings = client.app.state.settings
    with TestClient(create_app(settings)) as restarted:
        assert restarted.get(f"/api/v1/trips/{trip['id']}", headers=owner).json()["version"] == 1
