from contextlib import contextmanager
from pathlib import Path
import sqlite3


SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS trips (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL REFERENCES users(id),
    invite_code TEXT NOT NULL UNIQUE,
    draft_json TEXT NOT NULL,
    published_json TEXT,
    draft_revision INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'planning' CHECK(status IN ('planning','active','finished')),
    current_block_id TEXT,
    updated_at TEXT NOT NULL,
    published_at TEXT
);
CREATE TABLE IF NOT EXISTS memberships (
    trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_after_id INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(trip_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON memberships(user_id);
CREATE TABLE IF NOT EXISTS personalizations (
    trip_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    config_json TEXT NOT NULL,
    PRIMARY KEY(trip_id,user_id),
    FOREIGN KEY(trip_id,user_id) REFERENCES memberships(trip_id,user_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS trip_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    trip_title TEXT NOT NULL,
    kind TEXT NOT NULL,
    actor_id TEXT NOT NULL REFERENCES users(id),
    target_user_id TEXT REFERENCES users(id),
    message TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trip_events_trip ON trip_events(trip_id,id);
CREATE TABLE IF NOT EXISTS station_cache (
    code TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    search_title TEXT NOT NULL,
    latitude REAL,
    longitude REAL,
    transport_type TEXT NOT NULL DEFAULT 'train'
);
CREATE TABLE IF NOT EXISTS walking_cache (
    cache_key TEXT PRIMARY KEY,
    route_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS place_search_cache (
    cache_key TEXT PRIMARY KEY,
    results_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS cache_metadata (
    key TEXT PRIMARY KEY,
    updated_at TEXT NOT NULL
);
"""


class Database:
    def __init__(self, path: Path):
        self.path = Path(path)

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=15, isolation_level=None, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 15000")
        try:
            yield connection
        finally:
            connection.close()

    def initialize(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(SCHEMA)
            # Additive migrations preserve existing accounts, plans and caches.
            with transaction(connection):
                member_columns = {row["name"] for row in connection.execute("PRAGMA table_info(memberships)")}
                if "event_after_id" not in member_columns:
                    connection.execute("ALTER TABLE memberships ADD COLUMN event_after_id INTEGER NOT NULL DEFAULT 0")
                station_columns = {row["name"] for row in connection.execute("PRAGMA table_info(station_cache)")}
                if "transport_type" not in station_columns:
                    connection.execute("ALTER TABLE station_cache ADD COLUMN transport_type TEXT NOT NULL DEFAULT 'train'")


@contextmanager
def transaction(connection: sqlite3.Connection):
    connection.execute("BEGIN IMMEDIATE")
    try:
        yield connection
        connection.execute("COMMIT")
    except BaseException:
        connection.execute("ROLLBACK")
        raise
