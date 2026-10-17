"""Remove MapKit responses after their permitted 30-day cache window.

User-authored places and notes are kept. A new path must be requested from
MapKit before publishing an expired walk again.
"""
from datetime import datetime, timedelta
import json

from .security import utcnow
from .database import transaction


MAPKIT_CACHE_DAYS = 30


def prune_plan_json(raw: str | None, now: datetime | None = None) -> tuple[str | None, bool]:
    if raw is None:
        return None, False
    now = now or utcnow()
    plan = json.loads(raw)
    changed = False
    for block in plan.get("blocks", []):
        walk = block.get("walk")
        if not walk or walk.get("routing_source") == "valhalla_osm":
            continue
        routed_at = walk.get("routed_at")
        try:
            routed_at = datetime.fromisoformat(routed_at.replace("Z", "+00:00")) if routed_at else None
            fresh = routed_at is not None and routed_at.tzinfo is not None and routed_at > now - timedelta(days=MAPKIT_CACHE_DAYS)
        except (TypeError, ValueError):
            fresh = False
        if fresh:
            continue
        if walk.get("geometry") or walk.get("distance_meters") is not None or walk.get("duration_seconds") is not None or routed_at:
            walk.update(geometry=[], distance_meters=None, duration_seconds=None, routed_at=None)
            changed = True
    return (json.dumps(plan, ensure_ascii=False), True) if changed else (raw, False)


def prune_trip_row(db, row):
    if row is None:
        return None
    if not db.in_transaction:
        # A read may race an organizer saving a newer draft. Re-read while
        # holding the write lock before pruning either JSON snapshot.
        with transaction(db):
            latest = db.execute("SELECT * FROM trips WHERE id=?", (row["id"],)).fetchone()
            return prune_trip_row(db, latest)
    draft_json, draft_changed = prune_plan_json(row["draft_json"])
    published_json, published_changed = prune_plan_json(row["published_json"])
    if draft_changed or published_changed:
        db.execute("UPDATE trips SET draft_json=?,published_json=? WHERE id=?", (draft_json, published_json, row["id"]))
        return db.execute("SELECT * FROM trips WHERE id=?", (row["id"],)).fetchone()
    return row
