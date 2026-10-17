"""Yandex Rasp v3 adapter. Provider keys never leave this module's HTTP headers."""
import asyncio
from datetime import date, datetime, timedelta, timezone
import math
import re
import sqlite3
import unicodedata
from urllib.parse import parse_qs, urlparse

import httpx
from fastapi import HTTPException
from pydantic import ValidationError

from .config import Settings
from .database import Database, transaction
from .models import Service, Station, Stop, ThreadDetails
from .security import utcnow


BASE_URL = "https://api.rasp.yandex-net.ru/v3.0/"
SOURCE_URL = "https://rasp.yandex.ru/"
NO_GEOMETRY = "Яндекс Расписания предоставляет остановки, но не точную геометрию рельсов."
BUS_NO_GEOMETRY = "Остановки автобуса сохранены. Автомобильный путь рейса не загружен."


def search_text(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold().replace("ё", "е").strip()


def coordinates(value: dict) -> tuple[float | None, float | None]:
    try:
        latitude, longitude = float(value.get("latitude")), float(value.get("longitude"))
        if not (math.isfinite(latitude) and math.isfinite(longitude)
                and -90 <= latitude <= 90 and -180 <= longitude <= 180):
            return None, None
        if latitude == 0 and longitude == 0:
            return None, None
        return latitude, longitude
    except (TypeError, ValueError):
        return None, None


def directory_stations(data: dict) -> list[tuple[Station, str]]:
    stations = {}
    for country in data.get("countries", []):
        for region in country.get("regions", []):
            for settlement in region.get("settlements", []):
                for raw in settlement.get("stations", []):
                    transport = raw.get("transport_type")
                    if transport not in ("train", "suburban", "bus"):
                        continue
                    code = raw.get("codes", {}).get("yandex_code")
                    title = str(raw.get("title", "")).strip()
                    if not code or not title:
                        continue
                    if transport == "bus":
                        # Many towns have an identically named "Автостанция" or
                        # "Центр". Keep the provider's station code, add locality
                        # context to its displayed/searchable name only.
                        town = str(settlement.get("title", "")).strip()
                        area = str(region.get("title", "")).strip()
                        local_title = re.sub(r"\W+", " ", search_text(title)).strip()
                        local_town = re.sub(r"\W+", " ", search_text(town)).strip()
                        if town and local_town not in local_title:
                            title = f"{town}, {title}"
                        if area and search_text(area) not in search_text(title):
                            title = f"{title} · {area}"
                    latitude, longitude = coordinates(raw)
                    stations[code] = (Station(code=code, title=title[:300], latitude=latitude, longitude=longitude), transport)
    return list(stations.values())


def flatten_stations(data: dict) -> list[Station]:
    return [station for station, _ in directory_stations(data)]


class YandexRasp:
    def __init__(self, settings: Settings, database: Database, client: httpx.AsyncClient):
        self.settings = settings
        self.database = database
        self.client = client
        self._station_lock = asyncio.Lock()

    def require_configured(self):
        if not self.settings.yandex_rasp_api_key:
            raise HTTPException(503, "Расписания не настроены: добавьте YANDEX_RASP_API_KEY в server/.env и перезапустите сервер.")

    async def request(self, method: str, params: dict) -> dict:
        self.require_configured()
        try:
            response = await self.client.get(
                BASE_URL + method + "/",
                params={"format": "json", "lang": "ru_RU", **params},
                headers={
                    "Authorization": self.settings.yandex_rasp_api_key,
                    "Referer": f"http://{self.settings.android_package}.yraspapp",
                },
                timeout=self.settings.provider_timeout_seconds,
            )
            if response.status_code in (401, 403):
                raise HTTPException(503, "Яндекс Расписания отклонил ключ API. Проверьте настройку ключа на сервере.")
            if response.status_code == 429:
                raise HTTPException(503, "Лимит запросов к Яндекс Расписаниям исчерпан. Попробуйте позже.")
            if response.status_code == 404:
                raise HTTPException(404, "Рейс или станции не найдены. Повторите поиск: идентификатор рейса мог измениться.")
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or data.get("error"):
                raise ValueError("Invalid provider payload")
            return data
        except httpx.TimeoutException:
            raise HTTPException(503, "Яндекс Расписания не ответил вовремя. Сохранённые путешествия доступны.") from None
        except (httpx.HTTPError, ValueError):
            raise HTTPException(502, "Не удалось получить данные Яндекс Расписаний. Попробуйте позже.") from None

    def _cache_is_fresh(self) -> bool:
        with self.database.connect() as connection:
            row = connection.execute("SELECT updated_at FROM cache_metadata WHERE key='stations-v2'").fetchone()
        if not row:
            return False
        return datetime.fromisoformat(row["updated_at"]) > utcnow() - timedelta(hours=self.settings.station_cache_hours)

    async def ensure_stations(self, mode: str = "suburban"):
        self.require_configured()
        if self._cache_is_fresh():
            return
        async with self._station_lock:
            if self._cache_is_fresh():
                return
            try:
                raw_directory = await self.request("stations_list", {})
                try:
                    stations = directory_stations(raw_directory)
                except (AttributeError, KeyError, TypeError, ValueError):
                    raise HTTPException(502, "Не удалось прочитать справочник станций поставщика.") from None
                if not stations:
                    raise HTTPException(502, "Поставщик не вернул станции и остановки.")
                with self.database.connect() as connection, transaction(connection):
                    connection.execute("DELETE FROM station_cache")
                    connection.executemany(
                        "INSERT INTO station_cache(code,title,search_title,latitude,longitude,transport_type) VALUES (?,?,?,?,?,?)",
                        [(s.code, s.title, search_text(s.title), s.latitude, s.longitude, mode) for s, mode in stations],
                    )
                    connection.execute("INSERT OR REPLACE INTO cache_metadata(key,updated_at) VALUES('stations-v2',?)",
                                       (utcnow().isoformat(),))
            except HTTPException:
                # The directory is not live schedule data. An existing directory remains
                # useful during an outage; missing API configuration still errors above.
                with self.database.connect() as connection:
                    has_cache = connection.execute(
                        "SELECT 1 FROM station_cache WHERE " + self.station_mode_filter(mode) + " LIMIT 1"
                    ).fetchone()
                if not has_cache:
                    raise

    @staticmethod
    def station_mode_filter(mode: str) -> str:
        if mode not in ("suburban", "train", "bus"):
            raise HTTPException(422, "Неизвестный тип транспорта.")
        # Rail stops are shared by suburban and long-distance trains. A legacy
        # railway-only cache receives 'train' through the SQLite migration.
        return "transport_type='bus'" if mode == "bus" else "transport_type IN ('train','suburban')"

    async def search_stations(self, query: str, mode: str = "suburban") -> list[Station]:
        mode_filter = self.station_mode_filter(mode)
        await self.ensure_stations(mode)
        normalized = search_text(query)
        if len(normalized) < 2:
            raise HTTPException(422, "Введите хотя бы два символа названия станции.")
        # Match words independently: the directory stores names such as
        # "Москва (Ярославский вокзал)" and "Ярославль-Главный".
        # A literal phrase match hid these behind similarly named service points.
        terms = list(dict.fromkeys(re.sub(r"(?:ский|ская|ское|ские)$", "ск", word)
                                   for word in re.findall(r"\w+", normalized)))
        if not terms:
            return []
        escaped_terms = [word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") for word in terms]
        conditions = " AND ".join("search_title LIKE ? ESCAPE '\\'" for _ in terms)
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT code,title,latitude,longitude FROM station_cache WHERE " + mode_filter + " AND " + conditions +
                " ORDER BY CASE WHEN search_title=? THEN 0 ELSE 1 END, "
                "CASE WHEN search_title LIKE '%вокзал%' THEN 0 ELSE 1 END, "
                "CASE WHEN search_title LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, "
                "length(title),title LIMIT 30",
                [*(f"%{term}%" for term in escaped_terms), normalized, f"{escaped_terms[0]}%"],
            ).fetchall()
        return [Station(**dict(row)) for row in rows]

    def station(self, raw: dict) -> Station:
        code = raw.get("code") or raw.get("codes", {}).get("yandex_code")
        with self.database.connect() as connection:
            row = connection.execute("SELECT code,title,latitude,longitude FROM station_cache WHERE code=?", (code,)).fetchone()
        if row:
            return Station(**dict(row))
        latitude, longitude = coordinates(raw)
        return Station(code=code, title=raw.get("title", code), latitude=latitude, longitude=longitude)

    def normalize_service(self, segment: dict, fetched_at: datetime, mode: str = "suburban") -> Service:
        thread = segment["thread"]
        departure = datetime.fromisoformat(segment["departure"])
        arrival = datetime.fromisoformat(segment["arrival"])
        # The provider's generated thread link carries the originating service
        # date, which may precede boarding. Parse metadata only; never follow an
        # arbitrary URL supplied by an upstream response.
        generated_link = thread.get("thread_method_link") or ""
        link_date = parse_qs(urlparse(generated_link).query).get("date", [None])[0]
        service_date = link_date or segment.get("start_date") or departure.date()
        return Service(
            uid=thread["uid"], number=str(thread.get("number") or ""), title=thread.get("title") or "",
            service_date=service_date,
            departure=departure, arrival=arrival,
            duration_seconds=round((arrival - departure).total_seconds()),
            from_station=self.station(segment["from"]), to_station=self.station(segment["to"]),
            stops=[], geometry=[], geometry_note=BUS_NO_GEOMETRY if mode == "bus" else NO_GEOMETRY,
            source_url=SOURCE_URL, fetched_at=fetched_at,
        )

    async def services(self, from_code: str, to_code: str, travel_date: date, mode: str) -> list[Service]:
        await self.ensure_stations(mode)
        fetched_at = utcnow()
        services = []
        offset = 0
        try:
            while True:
                result = await self.request("search", {
                    "from": from_code, "to": to_code, "date": travel_date.isoformat(),
                    "transport_types": mode, "transfers": "false", "limit": 100, "offset": offset,
                })
                segments = result.get("segments", [])
                if not isinstance(segments, list):
                    raise ValueError("Segments must be a list")
                services.extend(self.normalize_service(segment, fetched_at, mode) for segment in segments
                                if not segment.get("has_transfers"))
                offset += len(segments)
                total = result.get("pagination", {}).get("total", offset)
                if not segments or offset >= total:
                    break
                if offset >= 1000:
                    raise HTTPException(502, "Поставщик вернул слишком много рейсов. Уточните станции поиска.")
            services.sort(key=lambda service: service.departure)
            return services
        except (KeyError, TypeError, ValueError, ValidationError):
            raise HTTPException(502, "Формат расписания поставщика изменился. Сохранённый план доступен.") from None

    @staticmethod
    def clip_thread(data: dict, from_code: str, to_code: str) -> list[dict]:
        stops = data.get("stops", [])
        start = next((i for i, stop in enumerate(stops) if stop["station"].get("code") == from_code), None)
        end = next((i for i, stop in enumerate(stops)
                    if start is not None and i > start and stop["station"].get("code") == to_code), None)
        if start is None or end is None:
            raise ValueError("Requested stations missing from thread")
        return stops[start:end + 1]

    @staticmethod
    def elapsed_seconds(value) -> float:
        if isinstance(value, bool):
            raise ValueError("Invalid elapsed time")
        seconds = float(value)
        if not math.isfinite(seconds) or seconds < 0:
            raise ValueError("Invalid elapsed time")
        return seconds

    @classmethod
    def stop_timestamp(cls, raw: dict, field: str, origin: datetime | None) -> datetime | None:
        value = raw.get(field)
        if value is None:
            return None
        parsed = datetime.fromisoformat(value)
        if parsed.tzinfo is not None and parsed.utcoffset() is not None:
            return parsed
        if origin is None:
            raise ValueError("Missing timezone anchor for a naive provider timestamp")
        # Live thread responses can omit UTC offsets. `duration` is cumulative
        # elapsed time to departure, or to arrival at a terminal with no departure.
        # Recover absolute instants without guessing a timezone for each station.
        timestamp = origin + timedelta(seconds=cls.elapsed_seconds(raw.get("duration")))
        if field == "arrival" and raw.get("departure") is not None:
            dwell = raw.get("stop_time")
            if dwell is None:
                departure = datetime.fromisoformat(raw["departure"])
                dwell = (departure.replace(tzinfo=None) - parsed.replace(tzinfo=None)).total_seconds()
            timestamp -= timedelta(seconds=cls.elapsed_seconds(dwell))
        return timestamp

    def normalize_thread(self, data: dict, from_code: str, to_code: str, fetched_at: datetime,
                         origin: datetime | None = None) -> ThreadDetails:
        stops = []
        for raw in self.clip_thread(data, from_code, to_code):
            seconds = raw.get("stop_time")
            stops.append(Stop(
                station=self.station(raw["station"]),
                arrival=self.stop_timestamp(raw, "arrival", origin),
                departure=self.stop_timestamp(raw, "departure", origin),
                stop_seconds=round(self.elapsed_seconds(seconds)) if seconds is not None else None,
            ))
        return ThreadDetails(stops=stops, geometry=[], source_url=SOURCE_URL,
                             fetched_at=fetched_at, geometry_note=BUS_NO_GEOMETRY if data.get("transport_type") == "bus" else NO_GEOMETRY)

    async def thread_origin(self, data: dict, uid: str, from_code: str, to_code: str) -> datetime | None:
        stops = self.clip_thread(data, from_code, to_code)
        timestamps = [datetime.fromisoformat(stop[field]) for stop in stops
                      for field in ("arrival", "departure") if stop.get(field) is not None]
        if all(value.tzinfo is not None and value.utcoffset() is not None for value in timestamps):
            return None
        boarding = stops[0]
        departure = datetime.fromisoformat(boarding["departure"])
        if departure.tzinfo is None or departure.utcoffset() is None:
            mode = data.get("transport_type") or "suburban"
            if mode not in ("suburban", "train", "bus"):
                raise ValueError("Unsupported service type")
            # Query the boarding date, not the origin date: overnight trains can
            # reach the selected station on a later day. Match UID, never number.
            services = await self.services(from_code, to_code, departure.date(), mode)
            selected = next((service for service in services if service.uid == uid), None)
            if selected is None:
                raise HTTPException(404, "Рейс изменился у поставщика. Повторите поиск и выберите его заново.")
            departure = selected.departure
        return departure.astimezone(timezone.utc) - timedelta(
            seconds=self.elapsed_seconds(boarding.get("duration")))

    async def thread(self, uid: str, travel_date: date, from_code: str, to_code: str) -> ThreadDetails:
        await self.ensure_stations()
        # Query the entire originating run to avoid the provider's ambiguous
        # date interpretation when `from` is an intermediate overnight station.
        data = await self.request("thread", {"uid": uid, "date": travel_date.isoformat()})
        try:
            origin = await self.thread_origin(data, uid, from_code, to_code)
            return self.normalize_thread(data, from_code, to_code, utcnow(), origin)
        except (KeyError, TypeError, ValueError, ValidationError):
            raise HTTPException(502, "Не удалось сопоставить остановки рейса. Повторите поиск расписания.") from None
