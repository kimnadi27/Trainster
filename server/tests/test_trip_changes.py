from concurrent.futures import ThreadPoolExecutor
import json
import sqlite3
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.database import Database, SCHEMA
from app.main import create_app


@pytest.fixture
def group(tmp_path):
    with TestClient(create_app(Settings(database=tmp_path / "changes.sqlite3"))) as client:
        users = []
        for username in ("organizer", "friend-one", "friend-two", "outsider"):
            result = client.post("/api/v1/auth/register", json={"username": username, "password": "isolated-test-password"}).json()
            users.append((result["user"], {"Authorization": f"Bearer {result['token']}"}))
        owner, first, second, stranger = users
        trip = client.post("/api/v1/trips", headers=owner[1], json={"title": "Поездка"}).json()
        blocks = [{"id": str(uuid4()), "kind": "pause", "title": title} for title in ("Встреча", "Пикник")]
        trip = save(client, owner, trip, blocks)
        trip = publish(client, owner, trip)
        join(client, first, trip)
        join(client, second, trip)
        yield client, owner, first, second, stranger, trip, blocks


def save(client, owner, trip, blocks):
    response = client.put(f"/api/v1/trips/{trip['id']}", headers=owner[1], json={
        "title": trip["title"], "description": "", "blocks": blocks, "expected_revision": trip["draft_revision"]})
    assert response.status_code == 200, response.text
    return response.json()


def publish(client, owner, trip):
    response = client.post(f"/api/v1/trips/{trip['id']}/publish", headers=owner[1], json={"expected_revision": trip["draft_revision"]})
    assert response.status_code == 200, response.text
    return response.json()


def join(client, member, trip):
    response = client.post("/api/v1/trips/join", headers=member[1], json={"code": trip["invite_code"]})
    assert response.status_code == 200, response.text
    return response.json()


def edit(client, owner, member, trip, **config):
    return client.put(f"/api/v1/trips/{trip['id']}/participants/{member[0]['id']}", headers=owner[1], json={
        "expected_revision": 0, "excluded_block_ids": [], "note": "", "block_notes": {}, **config})


def feed(client, user, **query):
    response = client.get("/api/v1/events", headers=user[1], params=query)
    assert response.status_code == 200, response.text
    return response.json()


def test_personalization_is_private_and_common_timeline_unchanged(group):
    client, owner, first, second, stranger, trip, blocks = group
    custom = {"excluded_block_ids": [blocks[0]["id"]], "note": "Присоединяйся позже",
              "block_notes": {blocks[1]["id"]: "Возьми термос"}}
    result = edit(client, owner, first, trip, **custom)
    assert result.status_code == 200, result.text
    assert result.json()["personalization"] == {"revision": 1, **custom}
    url = f"/api/v1/trips/{trip['id']}"
    own = client.get(url, headers=first[1]).json()
    assert own["personalization"] == {"revision": 1, **custom}
    assert [block["id"] for block in own["blocks"]] == [block["id"] for block in blocks]
    assert own["version"] == trip["version"]
    for viewer in (owner, second):
        content = client.get(url, headers=viewer[1]).text
        assert "Присоединяйся" not in content and "термос" not in content
        assert json.loads(content)["personalization"]["revision"] == 0
    members = client.get(url + "/participants", headers=owner[1]).json()
    assert len(members) == 2
    assert {member["user"]["id"] for member in members} == {first[0]["id"], second[0]["id"]}
    assert next(member for member in members if member["user"]["id"] == first[0]["id"])["personalization"]["note"] == custom["note"]
    assert client.get(url, headers=stranger[1]).status_code == 404


def test_only_owner_can_access_participants_or_change_personal_plans(group):
    client, owner, first, second, stranger, trip, blocks = group
    url = f"/api/v1/trips/{trip['id']}/participants"
    assert client.get(url).status_code == 401
    assert client.get("/api/v1/events").status_code == 401
    for viewer, status in ((first, 403), (second, 403), (stranger, 404)):
        assert client.get(url, headers=viewer[1]).status_code == status
        assert edit(client, viewer, first, trip, note="forbidden").status_code == status
    assert edit(client, owner, stranger, trip).status_code == 404
    assert edit(client, owner, owner, trip).status_code == 404


def test_personal_revision_and_published_ids_are_validated(group):
    client, owner, first, second, stranger, trip, blocks = group
    assert edit(client, owner, first, trip, expected_revision=7, note="stale").status_code == 409
    assert edit(client, owner, first, trip, excluded_block_ids=[str(uuid4())]).status_code == 422
    assert edit(client, owner, first, trip, excluded_block_ids=[blocks[0]["id"]] * 2).status_code == 422
    assert edit(client, owner, first, trip, block_notes={str(uuid4()): "unknown"}).status_code == 422
    assert edit(client, owner, first, trip, block_notes={blocks[0]["id"]: "x" * 10001}).status_code == 422
    latest = edit(client, owner, first, trip, note="new")
    assert latest.status_code == 200
    assert edit(client, owner, first, trip, note="old").status_code == 409
    assert edit(client, owner, first, trip, expected_revision=1, note="new").json()["personalization"]["revision"] == 1


def test_concurrent_personal_changes_have_one_winner(group):
    client, owner, first, second, stranger, trip, blocks = group
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda note: edit(client, owner, first, trip, note=note), ["Один", "Два"]))
    assert sorted(response.status_code for response in responses) == [200, 409]


def test_publish_prunes_removed_personal_ids_and_invalidates_stale_editor(group):
    client, owner, first, second, stranger, trip, blocks = group
    assert edit(client, owner, first, trip, excluded_block_ids=[blocks[0]["id"]], note="Личная заметка",
                block_notes={blocks[0]["id"]: "Удаляемая", blocks[1]["id"]: "Оставляемая"}).status_code == 200
    trip = save(client, owner, trip, [blocks[1]])
    trip = publish(client, owner, trip)
    config = client.get(f"/api/v1/trips/{trip['id']}", headers=first[1]).json()["personalization"]
    assert config == {"revision": 2, "excluded_block_ids": [], "note": "Личная заметка",
                      "block_notes": {blocks[1]["id"]: "Оставляемая"}}
    assert edit(client, owner, first, trip, expected_revision=1, note="stale").status_code == 409


def test_event_bootstrap_scope_private_targets_and_pagination(group):
    client, owner, first, second, stranger, trip, blocks = group
    bootstrap = feed(client, first)
    assert bootstrap["events"] == [] and bootstrap["cursor"] > 0
    cursor = bootstrap["cursor"]
    assert feed(client, first, after=0)["events"][0]["kind"] == "participant_joined"
    assert all(event["kind"] != "published" for event in feed(client, first, after=0)["events"])
    assert edit(client, owner, first, trip, note="НЕ ПОКАЗЫВАТЬ В СОБЫТИЯХ").status_code == 200
    state = {"status": "active", "current_block_id": blocks[0]["id"]}
    assert client.post(f"/api/v1/trips/{trip['id']}/state", headers=owner[1], json=state).status_code == 200
    first_page = feed(client, first, after=cursor, limit=1)
    assert first_page["has_more"] is True
    assert first_page["events"][0]["kind"] == "personalization_changed"
    assert "НЕ ПОКАЗЫВАТЬ" not in json.dumps(first_page, ensure_ascii=False)
    second_page = feed(client, first, after=first_page["cursor"], limit=1)
    assert second_page["has_more"] is False
    assert second_page["events"][0]["kind"] == "state_changed"
    assert "Путешествие началось" in second_page["events"][0]["message"]
    other = feed(client, second, after=cursor)
    assert [event["kind"] for event in other["events"]] == ["state_changed"]
    invisible = feed(client, stranger, after=0)
    assert invisible["events"] == [] and invisible["cursor"] == second_page["cursor"]
    assert feed(client, first, after=second_page["cursor"])["events"] == []
    reset = feed(client, first, after=second_page["cursor"] + 100)
    assert reset["reset"] and reset["events"] == [] and reset["cursor"] == second_page["cursor"]


def test_drafts_and_idempotent_actions_do_not_create_notifications(group):
    client, owner, first, second, stranger, trip, blocks = group
    cursor = feed(client, owner)["cursor"]
    join(client, first, trip)
    publish(client, owner, trip)
    assert edit(client, owner, first, trip).status_code == 200
    assert client.post(f"/api/v1/trips/{trip['id']}/state", headers=owner[1],
                       json={"status": "planning", "current_block_id": None}).status_code == 200
    changed = save(client, owner, trip, [*blocks, {"id": str(uuid4()), "kind": "pause", "title": "Черновик"}])
    assert feed(client, owner, after=cursor)["events"] == []
    assert publish(client, owner, changed)["version"] == trip["version"] + 1
    assert [event["kind"] for event in feed(client, first, after=cursor)["events"]] == ["published"]


def test_change_and_event_commit_atomically(group):
    client, owner, first, second, stranger, trip, blocks = group
    with client.app.state.database.connect() as db:
        db.execute("CREATE TRIGGER fail_change_event BEFORE INSERT ON trip_events WHEN NEW.kind='state_changed' "
                   "BEGIN SELECT RAISE(ABORT,'isolated failure'); END")
    with pytest.raises(sqlite3.IntegrityError, match="isolated failure"):
        client.post(f"/api/v1/trips/{trip['id']}/state", headers=owner[1],
                    json={"status": "active", "current_block_id": blocks[0]["id"]})
    reloaded = client.get(f"/api/v1/trips/{trip['id']}", headers=first[1]).json()
    assert reloaded["status"] == "planning" and reloaded["current_block_id"] is None


def test_personalization_and_event_feed_persist_after_restart(group):
    client, owner, first, second, stranger, trip, blocks = group
    cursor = feed(client, first)["cursor"]
    assert edit(client, owner, first, trip, note="Сохранится после перезапуска").status_code == 200
    with TestClient(create_app(client.app.state.settings)) as restarted:
        assert restarted.get(f"/api/v1/trips/{trip['id']}", headers=first[1]).json()["personalization"]["revision"] == 1
        assert feed(restarted, first, after=cursor)["events"][0]["kind"] == "personalization_changed"


def test_legacy_migration_preserves_memberships_station_cache_and_is_repeatable(tmp_path):
    path = tmp_path / "legacy.sqlite3"
    old_schema = SCHEMA.replace("    event_after_id INTEGER NOT NULL DEFAULT 0,\n", "").replace(
        "    longitude REAL,\n    transport_type TEXT NOT NULL DEFAULT 'train'", "    longitude REAL")
    with sqlite3.connect(path) as db:
        db.executescript(old_schema)
        db.execute("INSERT INTO users VALUES ('owner','organizer','hashed','2026-01-01')")
        db.execute("INSERT INTO users VALUES ('friend','friend','hashed','2026-01-01')")
        db.execute("INSERT INTO trips(id,owner_id,invite_code,draft_json,updated_at) VALUES ('trip','owner','CODE','{}','2026-01-01')")
        db.execute("INSERT INTO memberships(trip_id,user_id) VALUES ('trip','friend')")
        db.execute("INSERT INTO station_cache VALUES ('s123','Вокзал','вокзал',55.7,37.6)")
    database = Database(path)
    database.initialize()
    database.initialize()
    with database.connect() as db:
        assert dict(db.execute("SELECT * FROM memberships").fetchone()) == {"trip_id": "trip", "user_id": "friend", "event_after_id": 0}
        assert db.execute("SELECT transport_type FROM station_cache").fetchone()[0] == "train"
        assert db.execute("SELECT draft_json FROM trips").fetchone()[0] == "{}"
