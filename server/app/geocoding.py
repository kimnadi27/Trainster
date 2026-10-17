"""Small, user-triggered place searches through configurable Photon / OSM.

No autocomplete/background queries. The cache key is hashed and contains no
account identifier; requests and results never contain the participant's GPS.
"""
import asyncio
from datetime import datetime, timedelta
import hashlib
import json
import time

from fastapi import HTTPException
import httpx

from .models import PlaceSearchResult, Point
from .security import utcnow


def text_property(properties, key):
    value = properties.get(key)
    return value.strip() if isinstance(value, str) else ""


def parse_places(payload) -> list[PlaceSearchResult]:
    if not isinstance(payload, dict) or not isinstance(payload.get("features"), list):
        raise ValueError("Invalid feature collection")
    results = []
    seen = set()
    for feature in payload["features"][:20]:
        try:
            geometry, properties = feature["geometry"], feature["properties"]
            if geometry["type"] != "Point" or not isinstance(properties, dict):
                continue
            coordinates = geometry["coordinates"]
            if len(coordinates) < 2 or any(isinstance(c, bool) for c in coordinates[:2]):
                continue
            point = Point(longitude=coordinates[0], latitude=coordinates[1])
            street = " ".join(filter(None, [text_property(properties, "street"), text_property(properties, "housenumber")]))
            title = text_property(properties, "name") or street or text_property(properties, "city")
            if not title:
                continue
            parts = [street or title] + [text_property(properties, key) for key in ("district", "city", "county", "state", "country")]
            address = ", ".join(dict.fromkeys(part for part in parts if part)) or title
            osm_type, osm_id = properties.get("osm_type"), properties.get("osm_id")
            identifier = f"photon/{osm_type}/{osm_id}" if osm_type in ("N", "W", "R") and isinstance(osm_id, int) else "photon/" + hashlib.sha256(f"{point.latitude},{point.longitude},{title}".encode()).hexdigest()
            duplicate = (title.casefold(), round(point.latitude, 6), round(point.longitude, 6))
            if duplicate in seen:
                continue
            seen.add(duplicate)
            results.append(PlaceSearchResult(id=identifier, title=title[:300], address=address[:1500],
                                            latitude=point.latitude, longitude=point.longitude))
        except (KeyError, TypeError, ValueError, IndexError):
            continue
        if len(results) >= 6:
            break
    if payload["features"] and not results:
        raise ValueError("No usable places in response")
    return results


class PlaceSearch:
    def __init__(self, settings, database, client: httpx.AsyncClient):
        self.settings, self.database, self.client = settings, database, client
        self.lock = asyncio.Lock()
        self.last_request = 0.0

    async def search(self, query: str, near: Point | None = None) -> list[PlaceSearchResult]:
        query = " ".join(query.split())
        if not 3 <= len(query) <= 200:
            raise HTTPException(422, "Введите адрес или название места от 3 до 200 символов.")
        if not self.settings.place_search_url:
            raise HTTPException(503, "Поиск адресов не настроен на сервере. Место можно выбрать на карте.")
        # A coarse focus helps rank nearby places without sending device GPS.
        focus = [round(near.latitude, 3), round(near.longitude, 3)] if near else None
        key = hashlib.sha256(json.dumps(["photon-v2", self.settings.place_search_url, query.casefold(), focus], ensure_ascii=False).encode()).hexdigest()
        async with self.lock:
            with self.database.connect() as db:
                row = db.execute("SELECT results_json,updated_at FROM place_search_cache WHERE cache_key=?", (key,)).fetchone()
            if row and datetime.fromisoformat(row["updated_at"]) > utcnow() - timedelta(days=7):
                try:
                    return [PlaceSearchResult.model_validate(item) for item in json.loads(row["results_json"])]
                except (ValueError, TypeError):
                    pass
            await asyncio.sleep(max(0, self.last_request + 1.0 - time.monotonic()))
            self.last_request = time.monotonic()
            parameters = {"q": query, "limit": "6", "lang": "default"}
            if focus:
                parameters.update(lat=str(focus[0]), lon=str(focus[1]))
            try:
                response = await self.client.get(self.settings.place_search_url, params=parameters,
                    headers={"User-Agent": f"Trainster/0.3 ({self.settings.android_package})"}, timeout=20)
            except httpx.HTTPError as error:
                raise HTTPException(503, "Нет связи с поиском адресов. Попробуйте позже или выберите место на карте.") from error
            if response.status_code == 429:
                raise HTTPException(503, "Поиск адресов временно занят. Подождите или выберите место на карте.")
            if not response.is_success:
                raise HTTPException(502, "Сервис поиска адресов недоступен. Место можно выбрать на карте.")
            try:
                results = parse_places(response.json())
            except (TypeError, ValueError) as error:
                raise HTTPException(502, "Сервис поиска вернул некорректные места. Выберите точку на карте или повторите поиск.") from error
            with self.database.connect() as db:
                db.execute("INSERT OR REPLACE INTO place_search_cache VALUES (?,?,?)",
                           (key, json.dumps([item.model_dump() for item in results], ensure_ascii=False), utcnow().isoformat()))
                db.execute("DELETE FROM place_search_cache WHERE cache_key NOT IN (SELECT cache_key FROM place_search_cache ORDER BY updated_at DESC LIMIT 256)")
            return results
