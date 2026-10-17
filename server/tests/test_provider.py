from datetime import datetime, timezone

from fastapi.testclient import TestClient
import httpx
import pytest

from app.config import Settings
from app.main import create_app
from app.provider import flatten_stations


def station(code, title, lat="57.0", lon="39.0", transport="train"):
    return {"codes": {"yandex_code": code}, "title": title, "latitude": lat, "longitude": lon, "transport_type": transport}


DIRECTORY = {"countries": [{"regions": [{"settlements": [{"stations": [
    station("s1", "Ярославль"), station("s2", "Семибратово"), station("s3", "Ростов-Ярославский"),
    station("s4", "Москва"), station("s5", "Тест аэропорт", transport="plane"),
]}]}]}]}
SEGMENT = {
    "thread": {"uid": "rail-6002", "number": "6582/6581/6579", "title": "Ярославль — Ростов"},
    "from": {"code": "s1", "title": "Ярославль"}, "to": {"code": "s3", "title": "Ростов"},
    "departure": "2026-10-16T23:30:00+03:00", "arrival": "2026-10-17T01:00:00+03:00",
    "start_date": "2026-10-15", "duration": 5400,
}
THREAD = {"stops": [
    {"station": {"code": "s4", "title": "Москва"}, "arrival": None, "departure": "2026-10-16T20:00:00+03:00", "stop_time": None},
    {"station": {"code": "s1", "title": "Ярославль"}, "arrival": "2026-10-16T23:29:00+03:00", "departure": "2026-10-16T23:30:00+03:00", "stop_time": 60.0},
    {"station": {"code": "s2", "title": "Семибратово"}, "arrival": "2026-10-17T00:00:00+03:00", "departure": "2026-10-17T00:01:00+03:00", "stop_time": 60},
    {"station": {"code": "s3", "title": "Ростов"}, "arrival": "2026-10-17T01:00:00+03:00", "departure": None, "stop_time": None},
]}


@pytest.fixture
def provider_client(tmp_path):
    calls = []

    def handler(request):
        calls.append(request)
        if request.url.path.endswith("stations_list/"):
            return httpx.Response(200, json=DIRECTORY)
        if request.url.path.endswith("search/"):
            offset = request.url.params.get("offset")
            return httpx.Response(200, json={"segments": [SEGMENT] if offset == "0" else [], "pagination": {"total": 1}})
        if request.url.path.endswith("thread/"):
            return httpx.Response(200, json=THREAD)
        return httpx.Response(404)

    app = create_app(Settings(database=tmp_path / "provider.sqlite3", yandex_rasp_api_key="unit-test-secret"),
                     provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "tester", "password": "test-password"}).json()
        yield client, {"Authorization": f"Bearer {auth['token']}"}, calls


def test_directory_filters_non_rail_and_invalid_coordinates():
    directory = {"countries": [{"regions": [{"settlements": [{"stations": [
        station("s1", "Станция", "0", "0"), station("s2", "Ещё", "NaN", "12"),
        station("s3", "Самолёт", transport="plane"),
    ]}]}]}]}
    stations = flatten_stations(directory)
    assert len(stations) == 2
    assert all(s.latitude is None and s.longitude is None for s in stations)


def test_real_adapter_search_normalizes_overnight_number_and_cache(provider_client):
    client, headers, calls = provider_client
    assert client.get("/api/v1/stations?q=Ростов", headers=headers).json()[0]["code"] == "s3"
    assert client.get("/api/v1/stations?q=Ярославль", headers=headers).status_code == 200
    response = client.get("/api/v1/services?from_code=s1&to_code=s3&date=2026-10-16&mode=suburban", headers=headers)
    assert response.status_code == 200, response.text
    service = response.json()[0]
    assert service["number"] == "6582/6581/6579"
    assert service["duration_seconds"] == 5400
    assert service["service_date"] == "2026-10-15"
    assert service["geometry"] == []
    assert service["from_station"]["latitude"] == 57
    assert len([c for c in calls if "stations_list" in c.url.path]) == 1
    for call in calls:
        assert call.url.host == "api.rasp.yandex-net.ru"
        assert call.headers["Authorization"] == "unit-test-secret"
        assert "unit-test-secret" not in str(call.url)
    search = next(c for c in calls if "/search/" in c.url.path)
    assert search.url.params["date"] == "2026-10-16"
    assert search.url.params["transport_types"] == "suburban"
    assert search.url.params["transfers"] == "false"


def test_thread_clips_boarding_segment_and_preserves_timestamps(provider_client):
    client, headers, calls = provider_client
    response = client.get("/api/v1/services/thread?uid=rail-6002&date=2026-10-15&from_code=s1&to_code=s3", headers=headers)
    assert response.status_code == 200, response.text
    thread = response.json()
    assert [stop["station"]["code"] for stop in thread["stops"]] == ["s1", "s2", "s3"]
    assert thread["stops"][1]["arrival"].startswith("2026-10-17T00:00:00")
    assert thread["stops"][0]["stop_seconds"] == 60
    assert thread["stops"][-1]["departure"] is None
    assert thread["geometry"] == []
    request = calls[-1]
    assert request.url.params["date"] == "2026-10-15"
    assert "from" not in request.url.params
    assert "to" not in request.url.params


def test_provider_key_failure_does_not_leak_secret(tmp_path):
    app = create_app(Settings(database=tmp_path / "bad.sqlite3", yandex_rasp_api_key="do-not-leak"),
                     provider_transport=httpx.MockTransport(lambda request: httpx.Response(403)))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "tester", "password": "test-password"}).json()
        response = client.get("/api/v1/stations?q=Ростов", headers={"Authorization": f"Bearer {auth['token']}"})
        assert response.status_code == 503
        assert "do-not-leak" not in response.text


def test_provider_timeout_maps_to_honest_unavailable(tmp_path):
    def timeout(request):
        raise httpx.ReadTimeout("upstream unavailable", request=request)

    app = create_app(Settings(database=tmp_path / "timeout.sqlite3", yandex_rasp_api_key="secret"),
                     provider_transport=httpx.MockTransport(timeout))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "tester", "password": "test-password"}).json()
        response = client.get("/api/v1/stations?q=Ростов", headers={"Authorization": f"Bearer {auth['token']}"})
        assert response.status_code == 503


def test_bad_provider_schema_maps_to_502(provider_client):
    client, headers, calls = provider_client
    response = client.get("/api/v1/services/thread?uid=rail-6002&date=2026-10-16&from_code=missing&to_code=s3", headers=headers)
    assert response.status_code == 502


def test_malformed_station_directory_returns_502(tmp_path):
    app = create_app(Settings(database=tmp_path / "malformed.sqlite3", yandex_rasp_api_key="secret"),
                     provider_transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"countries": [None]})))
    with TestClient(app) as client:
        auth = client.post("/api/v1/auth/register", json={"username": "tester", "password": "test-password"}).json()
        response = client.get("/api/v1/stations?q=Ростов", headers={"Authorization": f"Bearer {auth['token']}"})
        assert response.status_code == 502


def test_naive_live_thread_times_recovered_from_dated_service(provider_client, monkeypatch):
    from copy import deepcopy
    import sys
    raw = deepcopy(THREAD)
    # Actual API format: local naive timestamps and cumulative departure duration.
    for stop, elapsed in zip(raw["stops"], [0, 12600, 14460, 18000]):
        stop["duration"] = elapsed
        for field in ("arrival", "departure"):
            if stop[field]:
                stop[field] = datetime.fromisoformat(stop[field]).replace(tzinfo=None).isoformat(sep=" ")
    monkeypatch.setattr(sys.modules[__name__], "THREAD", raw)
    client, headers, calls = provider_client
    response = client.get("/api/v1/services/thread?uid=rail-6002&date=2026-10-15&from_code=s1&to_code=s3", headers=headers)
    assert response.status_code == 200, response.text
    stops = response.json()["stops"]
    assert datetime.fromisoformat(stops[0]["departure"]).astimezone(timezone.utc) == datetime(2026, 10, 16, 20, 30, tzinfo=timezone.utc)
    assert datetime.fromisoformat(stops[1]["arrival"]).astimezone(timezone.utc) == datetime(2026, 10, 16, 21, 0, tzinfo=timezone.utc)
    assert datetime.fromisoformat(stops[-1]["arrival"]).astimezone(timezone.utc) == datetime(2026, 10, 16, 22, 0, tzinfo=timezone.utc)
    # Recover using boarding date, not originating date; always match exact UID.
    search = next(call for call in calls if "/search/" in call.url.path)
    assert search.url.params["date"] == "2026-10-16"
    assert stops[-1]["departure"] is None


def test_naive_stop_duration_handles_timezone_crossing_and_dwell():
    from app.provider import YandexRasp
    origin = datetime(2026, 10, 16, 20, tzinfo=timezone.utc)
    # Depart Moscow at 23:00+03, arrive a +05 station at 03:00 next day.
    raw = {"arrival": "2026-10-17 03:00:00", "departure": "2026-10-17 03:05:00", "duration": 7500, "stop_time": 300}
    assert YandexRasp.stop_timestamp(raw, "arrival", origin) == datetime(2026, 10, 16, 22, tzinfo=timezone.utc)
    assert YandexRasp.stop_timestamp(raw, "departure", origin) == datetime(2026, 10, 16, 22, 5, tzinfo=timezone.utc)
    # Missing dwell can be recovered from the two local events at one station.
    raw["stop_time"] = None
    assert YandexRasp.stop_timestamp(raw, "arrival", origin) == datetime(2026, 10, 16, 22, tzinfo=timezone.utc)


def test_naive_stop_without_verified_elapsed_time_is_rejected():
    from app.provider import YandexRasp
    origin = datetime(2026, 10, 16, 20, tzinfo=timezone.utc)
    with pytest.raises(ValueError):
        YandexRasp.stop_timestamp({"departure": "2026-10-16 23:00:00", "duration": 0}, "departure", None)
    with pytest.raises((ValueError, TypeError)):
        YandexRasp.stop_timestamp({"departure": "2026-10-16 23:00:00"}, "departure", origin)


@pytest.mark.parametrize("query", ["Москва Ярослав", "Ярослав Москва", "Москва Ярославская", "МОСКВА (ЯРОСЛАВ"])
def test_station_words_find_terminal_ahead_of_similarly_named_points(provider_client, monkeypatch, query):
    from copy import deepcopy
    import sys
    directory = deepcopy(DIRECTORY)
    directory["countries"][0]["regions"][0]["settlements"][0]["stations"].extend([
        station("s-tour", "Москва Ярославская Тур"),
        station("s-yard", "Москва-Тов.-Ярославская"),
        station("s-terminal", "Москва (Ярославский вокзал)"),
    ])
    monkeypatch.setattr(sys.modules[__name__], "DIRECTORY", directory)
    client, headers, calls = provider_client
    response = client.get("/api/v1/stations", params={"q": query}, headers=headers)
    assert response.status_code == 200, response.text
    assert response.json()[0]["code"] == "s-terminal"
    assert {station["code"] for station in response.json()} == {"s-terminal", "s-tour", "s-yard"}
