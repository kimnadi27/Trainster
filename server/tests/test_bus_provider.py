"""Bus uses the same real API contract as rail; fixtures cover its differences."""
from copy import deepcopy
from datetime import datetime, timezone

from fastapi.testclient import TestClient
import httpx
import pytest

from app.config import Settings
from app.database import Database
from app.main import create_app
from app.models import Transport
from app.provider import flatten_stations


def stop(code, title, transport="bus"):
    return {"codes": {"yandex_code": code}, "title": title, "latitude": 57.0,
            "longitude": 39.0, "transport_type": transport}


DIRECTORY = {"countries": [{"regions": [{"title": "Ярославская область", "settlements": [
    {"title": "Ростов", "stations": [stop("s-rail", "Ростов", "train"), stop("s-b1", "Автостанция")]},
    {"title": "Ярославль", "stations": [stop("s-b2", "Автовокзал")]},
]}]}]}
SEGMENT = {
    "thread": {"uid": "bus-107", "number": "107", "title": "Москва — Ярославль", "transport_type": "bus",
               "thread_method_link": "https://api.rasp.yandex-net.ru/v3.0/thread/?uid=bus-107&date=2026-10-16"},
    "from": {"code": "s-b1", "title": "Автостанция"}, "to": {"code": "s-b2", "title": "Автовокзал"},
    "departure": "2026-10-17T00:20:00+03:00", "arrival": "2026-10-17T01:20:00+03:00",
    "start_date": "2026-10-16", "duration": 3600,
}
THREAD = {"transport_type": "bus", "stops": [
    {"station": {"code": "s-b0", "title": "Москва"}, "arrival": None,
     "departure": "2026-10-16 23:50:00", "duration": 0, "stop_time": None},
    {"station": {"code": "s-b1", "title": "Автостанция"}, "arrival": "2026-10-17 00:19:00",
     "departure": "2026-10-17 00:20:00", "duration": 1800, "stop_time": 60},
    {"station": {"code": "s-mid", "title": "Промежуточная"}, "arrival": "2026-10-17 00:50:00",
     "departure": "2026-10-17 00:51:00", "duration": 3660, "stop_time": 60},
    {"station": {"code": "s-b2", "title": "Автовокзал"}, "arrival": "2026-10-17 01:20:00",
     "departure": None, "duration": 5400, "stop_time": None},
]}


@pytest.fixture
def bus_client(tmp_path):
    calls = []
    def handler(request):
        calls.append(request)
        if request.url.path.endswith("stations_list/"):
            return httpx.Response(200, json=DIRECTORY)
        if request.url.path.endswith("search/"):
            return httpx.Response(200, json={"segments": [SEGMENT], "pagination": {"total": 1}})
        if request.url.path.endswith("thread/"):
            return httpx.Response(200, json=THREAD)
        return httpx.Response(404)
    app = create_app(Settings(database=tmp_path / "buses.sqlite3", yandex_rasp_api_key="bus-unit-secret"),
                     provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "bus_tester", "password": "test-password"}).json()
        yield client, {"Authorization": f"Bearer {auth['token']}"}, calls


def test_bus_station_search_separates_rail_and_adds_locality(bus_client):
    client, headers, calls = bus_client
    bus = client.get("/api/v1/stations", params={"q": "Ростов", "mode": "bus"}, headers=headers)
    assert bus.status_code == 200, bus.text
    assert [station["code"] for station in bus.json()] == ["s-b1"]
    assert bus.json()[0]["title"] == "Ростов, Автостанция · Ярославская область"
    for mode in ("suburban", "train"):
        rail = client.get("/api/v1/stations", params={"q": "Ростов", "mode": mode}, headers=headers)
        assert [station["code"] for station in rail.json()] == ["s-rail"]
    assert len([call for call in calls if "stations_list" in call.url.path]) == 1


def test_bus_mode_requests_provider_and_preserves_exact_schedule(bus_client):
    client, headers, calls = bus_client
    response = client.get("/api/v1/services", params={"from_code": "s-b1", "to_code": "s-b2",
                          "date": "2026-10-17", "mode": "bus"}, headers=headers)
    assert response.status_code == 200, response.text
    service = response.json()[0]
    assert service["number"] == "107" and service["duration_seconds"] == 3600
    assert service["service_date"] == "2026-10-16"
    assert service["geometry"] == [] and "рельс" not in service["geometry_note"]
    transport = Transport(mode="bus", from_station=service["from_station"],
                          to_station=service["to_station"], service=service)
    assert transport.mode == "bus"
    search = next(call for call in calls if "/search/" in call.url.path)
    assert search.url.params["transport_types"] == "bus"
    assert search.url.params["date"] == "2026-10-17"
    for call in calls:
        assert call.headers["Authorization"] == "bus-unit-secret"
        assert "bus-unit-secret" not in str(call.url)


def test_bus_thread_recovers_naive_times_from_boarding_date(bus_client):
    client, headers, calls = bus_client
    response = client.get("/api/v1/services/thread", params={"uid": "bus-107", "date": "2026-10-16",
                          "from_code": "s-b1", "to_code": "s-b2"}, headers=headers)
    assert response.status_code == 200, response.text
    thread = response.json()
    assert [stop["station"]["code"] for stop in thread["stops"]] == ["s-b1", "s-mid", "s-b2"]
    assert datetime.fromisoformat(thread["stops"][0]["departure"]).astimezone(timezone.utc) == datetime(2026, 10, 16, 21, 20, tzinfo=timezone.utc)
    assert datetime.fromisoformat(thread["stops"][1]["arrival"]).astimezone(timezone.utc) == datetime(2026, 10, 16, 21, 50, tzinfo=timezone.utc)
    assert thread["stops"][1]["stop_seconds"] == 60
    assert "рельс" not in thread["geometry_note"]
    search = next(call for call in calls if "/search/" in call.url.path)
    assert search.url.params["transport_types"] == "bus"
    assert search.url.params["date"] == "2026-10-17"
    request = next(call for call in calls if "/thread/" in call.url.path)
    assert request.url.params["date"] == "2026-10-16"


def test_bus_geometry_never_queries_or_traces_railways(bus_client, monkeypatch):
    import app.rail_geometry as rail_geometry
    client, headers, _ = bus_client
    async def unexpected_trace(*args, **kwargs):
        pytest.fail("A bus schedule must not be traced over the railway network")
    monkeypatch.setattr(rail_geometry, "trace", unexpected_trace)
    response = client.get("/api/v1/services/geometry", params={"uid": "bus-107", "date": "2026-10-16",
                          "from_code": "s-b1", "to_code": "s-b2"}, headers=headers)
    assert response.status_code == 200, response.text
    assert response.json()["geometry"] == []
    assert "рельс" not in response.json()["geometry_note"]


def test_interval_only_buses_never_invent_departure_times(bus_client, monkeypatch):
    client, headers, _ = bus_client
    provider = client.app.state.provider
    original = provider.request
    async def request(method, params):
        if method == "search":
            return {"segments": [], "interval_segments": [{"thread": {"interval": {"density": "каждые 10 минут"}}}],
                    "pagination": {"total": 0}}
        return await original(method, params)
    monkeypatch.setattr(provider, "request", request)
    response = client.get("/api/v1/services", params={"from_code": "s-b1", "to_code": "s-b2",
                          "date": "2026-10-17", "mode": "bus"}, headers=headers)
    assert response.status_code == 200 and response.json() == []


def test_legacy_rail_cache_is_refreshed_for_new_bus_stops(bus_client):
    client, headers, calls = bus_client
    with client.app.state.database.connect() as connection:
        connection.execute("INSERT INTO station_cache(code,title,search_title) VALUES('s-old','Старое','старое')")
        connection.execute("INSERT INTO cache_metadata(key,updated_at) VALUES('stations',?)", (datetime.now(timezone.utc).isoformat(),))
    response = client.get("/api/v1/stations", params={"q": "Ростов", "mode": "bus"}, headers=headers)
    assert response.status_code == 200 and response.json()[0]["code"] == "s-b1"
    assert len([call for call in calls if "stations_list" in call.url.path]) == 1
    with client.app.state.database.connect() as connection:
        assert connection.execute("SELECT 1 FROM cache_metadata WHERE key='stations-v2'").fetchone()


def test_legacy_rail_cache_outage_stays_usable_but_does_not_claim_bus_coverage(tmp_path):
    db = Database(tmp_path / "legacy.sqlite3")
    db.initialize()
    with db.connect() as connection:
        connection.execute("INSERT INTO station_cache(code,title,search_title) VALUES('s-old','Ростов','ростов')")
        connection.execute("INSERT INTO cache_metadata(key,updated_at) VALUES('stations',?)", (datetime.now(timezone.utc).isoformat(),))
    def timeout(request):
        raise httpx.ReadTimeout("upstream unavailable", request=request)
    app = create_app(Settings(database=db.path, yandex_rasp_api_key="secret"), provider_transport=httpx.MockTransport(timeout))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "legacy_tester", "password": "test-password"}).json()
        headers = {"Authorization": f"Bearer {auth['token']}"}
        rail = client.get("/api/v1/stations?q=Ростов", headers=headers)
        assert rail.status_code == 200 and rail.json()[0]["code"] == "s-old"
        bus = client.get("/api/v1/stations?q=Ростов&mode=bus", headers=headers)
        assert bus.status_code == 503


def test_directory_ignores_planes_and_retains_bus_coordinates():
    directory = deepcopy(DIRECTORY)
    directory["countries"][0]["regions"][0]["settlements"][0]["stations"].append(stop("s-plane", "Аэропорт", "plane"))
    stations = flatten_stations(directory)
    assert {station.code for station in stations} == {"s-rail", "s-b1", "s-b2"}
    assert next(station for station in stations if station.code == "s-b1").latitude == 57


def test_bus_station_town_with_hyphen_is_not_repeated():
    directory = deepcopy(DIRECTORY)
    settlement = directory["countries"][0]["regions"][0]["settlements"][0]
    settlement["title"] = "Ростов Великий"
    settlement["stations"] = [stop("s-b1", "Ростов-Великий, ж/д вокзал")]
    station = next(station for station in flatten_stations(directory) if station.code == "s-b1")
    assert station.title == "Ростов-Великий, ж/д вокзал · Ярославская область"
