"""Best-effort railway tracing on real OpenStreetMap tracks.

This is an inferred path through the selected train's ordered stops, not an
official track assignment for that service. An unavailable path stays empty.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import time
from typing import Any
from uuid import uuid4

import httpx


DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
OSM_ATTRIBUTION = "© OpenStreetMap contributors"
OSM_URL = "https://www.openstreetmap.org/copyright"
SOURCE_NOTE = (
    "Путь рассчитан по железнодорожной сети OpenStreetMap через остановки рейса. "
    "Он может отличаться от фактического пути поезда и не определяет платформу. "
    + OSM_ATTRIBUTION
)
MAX_STATIONS = 250
MAX_GRAPH_NODES = 100_000
MAX_GEOMETRY_POINTS = 50_000
MAX_RESPONSE_BYTES = 12 * 1024 * 1024
MAX_CACHE_FILES = 128
MAX_CACHE_BYTES = 64 * 1024 * 1024
SNAP_RADIUS_METERS = 1_200.0
SNAP_CANDIDATES = 6
MAX_REGION_DIAGONAL_METERS = 500_000.0
MAX_REGION_AREA_METERS2 = 120_000_000_000.0
EARTH_RADIUS = 6_371_008.8

Coordinate = tuple[float, float]


class RailGeometryError(ValueError):
    """A requested inferred railway path cannot be calculated reliably."""


def _unavailable(note: str) -> dict[str, Any]:
    return {"geometry": [], "geometry_source": None, "geometry_note": note}


def _coordinate(value: dict[str, Any]) -> Coordinate | None:
    try:
        raw_lat, raw_lon = value.get("latitude"), value.get("longitude")
        if isinstance(raw_lat, bool) or isinstance(raw_lon, bool):
            return None
        lat, lon = float(raw_lat), float(raw_lon)
    except (TypeError, ValueError):
        return None
    if not (math.isfinite(lat) and math.isfinite(lon)):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat, lon) == (0, 0):
        return None
    return lat, lon


def _distance(a: Coordinate, b: Coordinate) -> float:
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlat, dlon = lat2 - lat1, math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS * math.asin(math.sqrt(min(1.0, h)))


def _stations(stations: list[dict[str, Any]]) -> tuple[list[Coordinate], int]:
    if len(stations) < 2 or len(stations) > MAX_STATIONS:
        raise RailGeometryError("Для трассировки нужны от 2 до 250 остановок рейса.")
    coordinates = [_coordinate(station) for station in stations]
    if coordinates[0] is None or coordinates[-1] is None:
        raise RailGeometryError("Нет координат станции посадки или высадки; путь пока недоступен.")
    skipped = sum(point is None for point in coordinates)
    usable = []
    for point in coordinates:
        if point is not None and (not usable or point != usable[-1]):
            usable.append(point)
    if len(usable) < 2:
        raise RailGeometryError("Не хватает различных координат остановок для трассировки.")
    return usable, skipped


def _bounds(points: list[Coordinate]) -> tuple[float, float, float, float]:
    south, north = min(p[0] for p in points), max(p[0] for p in points)
    west, east = min(p[1] for p in points), max(p[1] for p in points)
    # Five kilometres of context includes curves and station approaches.
    pad_lat = 5_000 / 111_195
    pad_lon = pad_lat / max(0.05, math.cos(math.radians((south + north) / 2)))
    south, north = max(-89.9, south - pad_lat), min(89.9, north + pad_lat)
    west, east = max(-180.0, west - pad_lon), min(180.0, east + pad_lon)
    height = _distance((south, west), (north, west))
    width = _distance(((south + north) / 2, west), ((south + north) / 2, east))
    if (east - west > 180 or _distance((south, west), (north, east)) > MAX_REGION_DIAGONAL_METERS
            or height * width > MAX_REGION_AREA_METERS2):
        raise RailGeometryError("Трассировка первой версии ограничена участками до 500 км. Разделите длинный этап.")
    return south, west, north, east


@dataclass(frozen=True)
class _Snap:
    edge: tuple[int, int]
    fraction: float
    point: Coordinate
    distance: float


def _projection(point: Coordinate, a: Coordinate, b: Coordinate) -> tuple[float, Coordinate, float]:
    # Local equirectangular projection is sufficient for an individual OSM edge.
    scale = max(0.001, math.cos(math.radians(point[0])))
    ax, ay = (a[1] - point[1]) * scale, a[0] - point[0]
    bx, by = (b[1] - point[1]) * scale, b[0] - point[0]
    dx, dy = bx - ax, by - ay
    denominator = dx * dx + dy * dy
    fraction = max(0.0, min(1.0, -(ax * dx + ay * dy) / denominator)) if denominator else 0.0
    snapped = (a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1]))
    return fraction, snapped, _distance(point, snapped)


def _build_graph(elements: list[dict[str, Any]], points: list[Coordinate]):
    nodes: dict[int, Coordinate] = {}
    edges: set[tuple[int, int]] = set()
    for way in elements:
        if not isinstance(way, dict):
            continue
        tags = way.get("tags", {})
        if not isinstance(tags, dict):
            continue
        if (way.get("type") != "way" or tags.get("railway") != "rail"
                or tags.get("service") in {"yard", "siding", "spur"}):
            continue
        ids, geometry = way.get("nodes", []), way.get("geometry", [])
        if not isinstance(ids, list) or not isinstance(geometry, list) or len(ids) != len(geometry):
            continue
        prior = None
        for node_id, position in zip(ids, geometry):
            if not isinstance(position, dict):
                prior = None
                continue
            point = _coordinate({"latitude": position.get("lat"), "longitude": position.get("lon")})
            if not isinstance(node_id, int) or node_id <= 0 or point is None:
                prior = None
                continue
            if node_id in nodes and nodes[node_id] != point:
                raise RailGeometryError("Источник карты вернул несовместимые координаты путей.")
            nodes[node_id] = point
            if prior is not None and prior != node_id:
                edges.add((min(prior, node_id), max(prior, node_id)))
            prior = node_id
        if len(nodes) > MAX_GRAPH_NODES:
            raise RailGeometryError("Слишком большой участок железнодорожной сети. Разделите этап.")
    if not edges:
        raise RailGeometryError("На этом участке не найдены железнодорожные пути OpenStreetMap.")

    # Retain a connected alternative even when a nearby isolated track has many
    # tiny segments. Merely taking six nearest edges could select only that track.
    components = {node: node for node in nodes}

    def component(node):
        while components[node] != node:
            components[node] = components[components[node]]
            node = components[node]
        return node

    for start, end in edges:
        components[component(start)] = component(end)

    snaps: list[list[_Snap]] = []
    for point in points:
        candidates = []
        for edge in edges:
            fraction, projection, distance = _projection(point, nodes[edge[0]], nodes[edge[1]])
            if distance <= SNAP_RADIUS_METERS:
                candidates.append(_Snap(edge, fraction, projection, distance))
        candidates.sort(key=lambda candidate: (candidate.distance, candidate.edge))
        if not candidates:
            raise RailGeometryError("Одна из остановок находится дальше 1,2 км от найденной железной дороги.")
        selected, seen_components = [], set()
        for candidate in candidates:
            group = component(candidate.edge[0])
            if group not in seen_components:
                selected.append(candidate)
                seen_components.add(group)
                if len(selected) == SNAP_CANDIDATES:
                    break
        chosen_edges = {candidate.edge for candidate in selected}
        for candidate in candidates:
            if len(selected) == SNAP_CANDIDATES:
                break
            if candidate.edge not in chosen_edges:
                selected.append(candidate)
                chosen_edges.add(candidate.edge)
        snaps.append(selected)

    # Insert projections on actual edges: never connect stops with a straight line.
    split: dict[tuple[int, int], list[tuple[float, int]]] = {edge: [(0.0, edge[0]), (1.0, edge[1])] for edge in edges}
    candidates_by_station: list[dict[int, float]] = []
    virtual_id = -1
    for station_snaps in snaps:
        candidates = {}
        for snap in station_snaps:
            if snap.fraction < 1e-8:
                node_id = snap.edge[0]
            elif snap.fraction > 1 - 1e-8:
                node_id = snap.edge[1]
            else:
                node_id = virtual_id
                virtual_id -= 1
                nodes[node_id] = snap.point
                split[snap.edge].append((snap.fraction, node_id))
            candidates[node_id] = min(candidates.get(node_id, math.inf), snap.distance)
        candidates_by_station.append(candidates)

    graph: dict[int, dict[int, float]] = {node_id: {} for node_id in nodes}
    for edge_points in split.values():
        ordered = sorted(edge_points)
        for (_, start), (_, end) in zip(ordered, ordered[1:]):
            length = _distance(nodes[start], nodes[end])
            graph[start][end] = length
            graph[end][start] = length
    return nodes, graph, candidates_by_station


def _advance(graph, previous: dict[int, float], candidates: dict[int, float]):
    """Multi-source Dijkstra preserves connectivity across every ordered stop."""
    distances, roots = dict(previous), {node: node for node in previous}
    parents: dict[int, int] = {}
    queue = [(cost, node) for node, cost in previous.items()]
    heapq.heapify(queue)
    remaining = set(candidates)
    while queue and remaining:
        cost, node = heapq.heappop(queue)
        if cost != distances[node]:
            continue
        remaining.discard(node)
        for neighbor, length in graph[node].items():
            next_cost = cost + length
            if next_cost < distances.get(neighbor, math.inf):
                distances[neighbor] = next_cost
                roots[neighbor] = roots[node]
                parents[neighbor] = node
                heapq.heappush(queue, (next_cost, neighbor))
    costs, transitions = {}, {}
    for target, snap_distance in candidates.items():
        if target not in distances:
            continue
        path = [target]
        while path[-1] in parents:
            path.append(parents[path[-1]])
        path.reverse()
        costs[target] = distances[target] + 4 * snap_distance
        transitions[target] = (roots[target], path)
    if not costs:
        raise RailGeometryError("Не найден связный железнодорожный путь через все известные остановки рейса.")
    return costs, transitions


def trace_graph(stations: list[dict[str, Any]], elements: list[dict[str, Any]]) -> dict[str, Any]:
    """Pure graph calculation, also used by tests; failure is explicit and empty."""
    try:
        points, skipped = _stations(stations)
        _bounds(points)
        nodes, graph, candidates = _build_graph(elements, points)
        costs = {node: distance * 4 for node, distance in candidates[0].items()}
        stages = []
        for next_candidates in candidates[1:]:
            costs, transitions = _advance(graph, costs, next_candidates)
            stages.append(transitions)
        current = min(costs, key=costs.get)
        paths = []
        for stage in reversed(stages):
            previous, path = stage[current]
            paths.append(path)
            current = previous
        geometry = []
        for path in reversed(paths):
            for node in path:
                point = {"latitude": nodes[node][0], "longitude": nodes[node][1]}
                if not geometry or point != geometry[-1]:
                    geometry.append(point)
        if len(geometry) < 2:
            raise RailGeometryError("Остановки не удалось различить на железнодорожной сети.")
        if len(geometry) > MAX_GEOMETRY_POINTS:
            raise RailGeometryError("Получен слишком подробный путь. Разделите этап на части.")
        note = SOURCE_NOTE
        if skipped:
            note += f" У {skipped} промежуточных остановок нет координат; они не использованы при расчёте."
        return {"geometry": geometry, "geometry_source": "derived_osm", "geometry_note": note}
    except RailGeometryError as exc:
        return _unavailable(str(exc))


class RailGeometryProvider:
    def __init__(self, endpoint: str = DEFAULT_ENDPOINT, cache_dir: Path | None = None,
                 cache_hours: float = 168, timeout_seconds: float = 35,
                 transport: httpx.AsyncBaseTransport | None = None):
        self.endpoint = endpoint
        self.cache_dir = cache_dir
        self.cache_seconds = max(0, min(cache_hours, 24 * 30)) * 3600
        self.timeout_seconds = max(1, min(timeout_seconds, 50))
        self.transport = transport

    @classmethod
    def from_env(cls):
        cache = os.environ.get("TRAINSTER_RAIL_CACHE_DIR")
        return cls(
            endpoint=os.environ.get("TRAINSTER_OVERPASS_URL", DEFAULT_ENDPOINT),
            cache_dir=Path(cache) if cache else Path(__file__).resolve().parents[1] / "data" / "rail_geometry",
            cache_hours=float(os.environ.get("TRAINSTER_RAIL_CACHE_HOURS", "168")),
        )

    def _cached(self, key: str):
        if self.cache_dir is None or not self.cache_seconds:
            return None
        try:
            path = self.cache_dir / f"{key}.json"
            if time.time() - path.stat().st_mtime > self.cache_seconds or path.stat().st_size > 5 * 1024 * 1024:
                return None
            result = json.loads(path.read_text(encoding="utf-8"))
            if (result.get("geometry_source") == "derived_osm" and isinstance(result.get("geometry"), list)
                    and 2 <= len(result["geometry"]) <= MAX_GEOMETRY_POINTS):
                return result
        except (OSError, ValueError, AttributeError):
            pass
        return None

    def _save(self, key: str, result):
        if self.cache_dir is None or not self.cache_seconds or not result.get("geometry"):
            return
        temp = None
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            payload = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
            if len(payload.encode("utf-8")) > 5 * 1024 * 1024:
                return
            temp = self.cache_dir / f"{key}.{uuid4().hex}.tmp"
            temp.write_text(payload, encoding="utf-8")
            temp.replace(self.cache_dir / f"{key}.json")
            entries = sorted(self.cache_dir.glob("*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
            total = 0
            for index, entry in enumerate(entries):
                info = entry.stat()
                total += info.st_size
                if (index >= MAX_CACHE_FILES or total > MAX_CACHE_BYTES
                        or time.time() - info.st_mtime > self.cache_seconds):
                    entry.unlink(missing_ok=True)
        except OSError:
            pass  # Disk cache failure must not prevent displaying a fetched route.
        finally:
            if temp is not None:
                try:
                    temp.unlink(missing_ok=True)
                except OSError:
                    pass

    async def trace(self, stations: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            points, _ = _stations(stations)
            bounds = _bounds(points)  # Reject oversized requests before cache or network access.
        except RailGeometryError as exc:
            return _unavailable(str(exc))
        key_data = ["v1", self.endpoint, stations]
        key = hashlib.sha256(json.dumps(key_data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        cached = self._cached(key)
        if cached is not None:
            return cached
        bbox = ",".join(f"{coordinate:.6f}" for coordinate in bounds)
        query = ('[out:json][timeout:25][maxsize:67108864];'
                 'way["railway"="rail"]["service"!~"^(yard|siding|spur)$"]'
                 f'({bbox});out geom;')
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds, transport=self.transport,
                                         headers={"User-Agent": "Trainster-MVP/0.1 (railway route planner)"}) as client:
                async with client.stream("POST", self.endpoint, data={"data": query}) as response:
                    response.raise_for_status()
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        content.extend(chunk)
                        if len(content) > MAX_RESPONSE_BYTES:
                            raise RailGeometryError("Источник карты вернул слишком большой участок. Разделите этап.")
            payload = json.loads(content)
            if not isinstance(payload, dict) or payload.get("remark") or not isinstance(payload.get("elements"), list):
                return _unavailable("Источник железнодорожных путей временно не смог выполнить расчёт.")
            result = await asyncio.to_thread(trace_graph, stations, payload["elements"])
            self._save(key, result)
            return result
        except RailGeometryError as exc:
            return _unavailable(str(exc))
        except (httpx.HTTPError, ValueError, TypeError):
            return _unavailable("Источник железнодорожных путей сейчас недоступен. Повторите расчёт при наличии связи.")


async def trace(stations: list[dict[str, Any]]) -> dict[str, Any]:
    """Convenient backend entry point; accepts normalized Station dictionaries."""
    return await RailGeometryProvider.from_env().trace(stations)
