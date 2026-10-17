"""Trip personalization and a durable, membership-scoped change stream."""
import json

from .models import Personalization
from .security import utcnow


def read_personalization(db, trip_id, user_id):
    row = db.execute(
        "SELECT revision,config_json FROM personalizations WHERE trip_id=? AND user_id=?",
        (trip_id, user_id),
    ).fetchone()
    if not row:
        return Personalization()
    return Personalization(revision=row["revision"], **json.loads(row["config_json"]))


def append_event(db, trip_id, trip_title, kind, actor_id, message, *, target_user_id=None):
    # Called inside the same transaction as the change; private notes never enter
    # event messages, and targeted events are filtered before being returned.
    db.execute(
        "INSERT INTO trip_events(trip_id,trip_title,kind,actor_id,target_user_id,message,created_at) "
        "VALUES (?,?,?,?,?,?,?)",
        (trip_id, trip_title, kind, actor_id, target_user_id, message, utcnow().isoformat()),
    )


def latest_cursor(db):
    return db.execute("SELECT COALESCE(MAX(id),0) FROM trip_events").fetchone()[0]


def prune_personalizations(db, trip_id, block_ids):
    for row in db.execute("SELECT user_id,config_json FROM personalizations WHERE trip_id=?", (trip_id,)).fetchall():
        original = json.loads(row["config_json"])
        config = {
            "excluded_block_ids": [key for key in original["excluded_block_ids"] if key in block_ids],
            "note": original["note"],
            "block_notes": {key: value for key, value in original["block_notes"].items() if key in block_ids},
        }
        if config != original:
            db.execute("UPDATE personalizations SET config_json=?,revision=revision+1 WHERE trip_id=? AND user_id=?",
                       (json.dumps(config, ensure_ascii=False), trip_id, row["user_id"]))


def event_page(db, user_id, after, limit):
    latest = latest_cursor(db)
    if after is None or after > latest:
        return {"events": [], "cursor": latest, "has_more": False, "reset": after is not None}
    rows = db.execute(
        "SELECT e.* FROM trip_events e JOIN trips t ON t.id=e.trip_id "
        "LEFT JOIN memberships m ON m.trip_id=e.trip_id AND m.user_id=? "
        "WHERE e.id>? AND e.id<=? AND (t.owner_id=? OR "
        "(m.user_id IS NOT NULL AND e.id>m.event_after_id AND "
        "(e.target_user_id IS NULL OR e.target_user_id=?))) ORDER BY e.id LIMIT ?",
        (user_id, after, latest, user_id, user_id, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    events = [dict(row) for row in rows[:limit]]
    return {"events": events, "cursor": events[-1]["id"] if has_more else latest,
            "has_more": has_more, "reset": False}
