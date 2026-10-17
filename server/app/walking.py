"""Real pedestrian routing through a configurable Valhalla instance.

The public demo is rate limited to one request per second per server process.
Only coordinates, geometry and metrics are cached; private place titles are not.
"""
import asyncio
from datetime import datetime, timedelta
import hashlib
import json
import math
import time

from fastapi import HTTPException
import httpx

from .models import Point, Walk, WalkPoint
from .security import utcnow


def decode_shape(encoded: str) -> list[Point]:
    if not isinstance(encoded, str) or len(encoded) > 600_000:
        raise ValueError("Invalid polyline")
    coordinates = []
    offset = latitude = longitude = 0
    while offset < len(encoded):
        deltas = []
        for _ in range(2):
            value = shift = 0
            while True:
                if offset >= len(encoded) or shift > 35:
                    raise ValueError("Truncated polyline")
                byte = ord(encoded[offset]) - 63
                offset += 1
                if not 0 <= byte <= 63:
                    raise ValueError("Invalid polyline character")
                value |= (byte & 31) << shift
                shift += 5
                if byte < 32:
                    break
            deltas.append(~(value >> 1) if value & 1 else value >> 1)
        latitude += deltas[0]
        longitude += deltas[1]
        coordinates.append(Point(latitude=latitude / 1_000_000, longitude=longitude / 1_000_000))
        if len(coordinates) > 50_000:
            raise ValueError("Too many coordinates")
    if len(coordinates) < 2:
        raise ValueError("Empty geometry")
    return coordinates


def validate_points(points: list[WalkPoint]):
    if not 2 <= len(points) <= 25 or len({point.id for point in points}) != len(points):
        raise HTTPException(422, "Для пути нужно от 2 до 25 разных мест.")
    length = 0.0
    for a, b in zip(points, points[1:]):
        lat1, lat2 = math.radians(a.latitude), math.radians(b.latitude)
        dlat, dlon = lat2 - lat1, math.radians(b.longitude - a.longitude)
        square = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        length += 6_371_000 * 2 * math.asin(min(1, math.sqrt(square)))
    if length > 50_000:
        raise HTTPException(422, "Пешеходный маршрут слишком длинный. Разделите прогулку на участки до 50 км.")


class WalkingRouter:
    def __init__(self, settings, database, client: httpx.AsyncClient):
        self.settings, self.database, self.client = settings, database, client
        self.lock = asyncio.Lock()
        self.last_request = 0.0

    def cached(self, key: str):
        with self.database.connect() as db:
            row = db.execute("SELECT route_json,updated_at FROM walking_cache WHERE cache_key=?", (key,)).fetchone()
        if row and datetime.fromisoformat(row["updated_at"]) > utcnow() - timedelta(days=7):
            try:
                return json.loads(row["route_json"])
            except (TypeError, ValueError):
                pass
        return None

    async def route(self, points: list[WalkPoint]) -> Walk:
        validate_points(points)
        locations = [{"lat": p.latitude, "lon": p.longitude} for p in points]
        key = hashlib.sha256(json.dumps([self.settings.walk_routing_url, locations], sort_keys=True).encode()).hexdigest()
        # The lock also deduplicates simultaneous requests for the same route.
        async with self.lock:
            cached = self.cached(key)
            if cached is not None:
                return Walk(points=points, **cached)
            await asyncio.sleep(max(0, self.last_request + 1.0 - time.monotonic()))
            self.last_request = time.monotonic()
            try:
                response = await self.client.post(
                    self.settings.walk_routing_url,
                    json={"locations": locations, "costing": "pedestrian", "units": "kilometers", "directions_type": "none"},
                    headers={"User-Agent": "Trainster/0.2 (ru.trainster.app)", "X-Client-Id": self.settings.android_package},
                    timeout=30,
                )
            except httpx.HTTPError as error:
                raise HTTPException(503, "Сервис пешеходных маршрутов недоступен. Места сохранены; попробуйте позже.") from error
            if response.status_code == 429:
                raise HTTPException(503, "Сервис маршрутов занят. Подождите и попробуйте снова.")
            if response.status_code in (400, 404):
                raise HTTPException(422, "Не удалось найти пешеходный путь. Проверьте точки и доступные дорожки.")
            if not response.is_success:
                raise HTTPException(502, "Сервис пешеходных маршрутов вернул ошибку.")
            try:
                trip = response.json()["trip"]
                if trip.get("status", 0) != 0 or not trip["legs"]:
                    raise ValueError("No route")
                geometry = []
                for leg in trip["legs"]:
                    path = decode_shape(leg["shape"])
                    geometry.extend(path[1:] if geometry and geometry[-1] == path[0] else path)
                summary = trip["summary"]
                walk = Walk(points=points, geometry=geometry, distance_meters=float(summary["length"]) * 1000,
                            duration_seconds=round(float(summary["time"])), routed_at=utcnow(), routing_source="valhalla_osm")
                if len(geometry) < 2:
                    raise ValueError("No geometry")
            except (ValueError, TypeError, KeyError, OverflowError) as error:
                raise HTTPException(502, "Сервис маршрутов вернул неполный путь. Попробуйте позже.") from error
            cached = walk.model_dump(mode="json", exclude={"points"})
            with self.database.connect() as db:
                db.execute("INSERT OR REPLACE INTO walking_cache VALUES (?,?,?)", (key, json.dumps(cached), utcnow().isoformat()))
                db.execute("DELETE FROM walking_cache WHERE cache_key NOT IN (SELECT cache_key FROM walking_cache ORDER BY updated_at DESC LIMIT 128)")
            return walk
