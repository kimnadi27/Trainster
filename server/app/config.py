from dataclasses import dataclass
import os
from pathlib import Path

from dotenv import load_dotenv


SERVER_DIR = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Settings:
    database: Path = SERVER_DIR / "data" / "trainster.sqlite3"
    yandex_rasp_api_key: str = ""
    session_days: int = 30
    station_cache_hours: int = 168
    provider_timeout_seconds: float = 30.0
    android_package: str = "ru.trainster.app"
    walk_routing_url: str = "https://valhalla1.openstreetmap.de/route"
    place_search_url: str = "https://photon.komoot.io/api/"

    @classmethod
    def from_env(cls) -> "Settings":
        load_dotenv(os.environ.get("TRAINSTER_ENV_FILE", str(SERVER_DIR / ".env")))
        db = Path(os.environ.get("TRAINSTER_DATABASE", "data/trainster.sqlite3"))
        if not db.is_absolute():
            db = SERVER_DIR / db
        return cls(
            database=db,
            yandex_rasp_api_key=os.environ.get("YANDEX_RASP_API_KEY", "").strip(),
            session_days=max(1, min(365, int(os.environ.get("TRAINSTER_SESSION_DAYS", "30")))),
            station_cache_hours=max(1, int(os.environ.get("TRAINSTER_STATION_CACHE_HOURS", "168"))),
            android_package=os.environ.get("TRAINSTER_ANDROID_PACKAGE", "ru.trainster.app"),
            walk_routing_url=os.environ.get("TRAINSTER_WALK_ROUTING_URL", "https://valhalla1.openstreetmap.de/route"),
            place_search_url=os.environ.get("TRAINSTER_PLACE_SEARCH_URL", "https://photon.komoot.io/api/").strip(),
        )
