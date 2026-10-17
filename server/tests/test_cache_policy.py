from datetime import timedelta
import json
from uuid import uuid4

from fastapi.testclient import TestClient

from app.cache_policy import prune_plan_json
from app.config import Settings
from app.main import create_app
from app.security import utcnow


def walk_plan(days):
    return {"title": "Город", "description": "Заметки", "blocks": [{
        "id": str(uuid4()), "kind": "walk", "title": "Прогулка", "notes": "Авторская заметка",
        "walk": {"points": [
            {"id": "one", "title": "Вокзал", "latitude": 57, "longitude": 39},
            {"id": "two", "title": "Музей", "latitude": 57.1, "longitude": 39.1}],
            "geometry": [{"latitude": 57, "longitude": 39}, {"latitude": 57.1, "longitude": 39.1}],
            "distance_meters": 1000, "duration_seconds": 800,
            "routed_at": (utcnow() - timedelta(days=days)).isoformat() if days is not None else None}
    }]}


def test_only_expired_or_unknown_age_mapkit_cache_removed():
    for days in (31, None):
        raw, changed = prune_plan_json(json.dumps(walk_plan(days)))
        assert changed
        block = json.loads(raw)["blocks"][0]
        assert len(block["walk"]["points"]) == 2
        assert block["notes"] == "Авторская заметка"
        assert block["walk"]["geometry"] == []
        assert block["walk"]["distance_meters"] is None
        assert block["walk"]["duration_seconds"] is None
    raw, changed = prune_plan_json(json.dumps(walk_plan(29)))
    assert not changed
    assert len(json.loads(raw)["blocks"][0]["walk"]["geometry"]) == 2


def test_reads_prune_both_snapshots_without_changing_user_revision(tmp_path):
    with TestClient(create_app(Settings(database=tmp_path / "cache.sqlite3"))) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "tester", "password": "test-password"}).json()
        headers = {"Authorization": f"Bearer {auth['token']}"}
        trip = client.post("/api/v1/trips", headers=headers, json={"title": "Город"}).json()
        old_plan = json.dumps(walk_plan(31))
        with client.app.state.database.connect() as db:
            db.execute("UPDATE trips SET draft_json=?,published_json=?,version=1 WHERE id=?", (old_plan, old_plan, trip["id"]))
        response = client.get(f"/api/v1/trips/{trip['id']}", headers=headers).json()
        assert response["blocks"][0]["walk"]["geometry"] == []
        assert response["draft_revision"] == 0
        assert response["version"] == 1
        with client.app.state.database.connect() as db:
            row = db.execute("SELECT * FROM trips WHERE id=?", (trip["id"],)).fetchone()
        assert json.loads(row["published_json"])["blocks"][0]["walk"]["geometry"] == []
        assert client.post(f"/api/v1/trips/{trip['id']}/publish", headers=headers,
                           json={"expected_revision": 0}).status_code == 422
