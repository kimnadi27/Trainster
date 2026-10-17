from datetime import timedelta
import json

from fastapi.testclient import TestClient
import httpx
import pytest

from app.cache_policy import prune_plan_json
from app.config import Settings
from app.main import create_app
from app.security import utcnow
from app.walking import decode_shape


def encode(points):
    output = ''
    previous = [0, 0]
    for pair in points:
        for index, number in enumerate(pair):
            value = round(number * 1_000_000)
            delta = value - previous[index]
            previous[index] = value
            delta = ~(delta << 1) if delta < 0 else delta << 1
            while delta >= 32:
                output += chr((32 | (delta & 31)) + 63)
                delta >>= 5
            output += chr(delta + 63)
    return output


POINTS = [dict(id='station', title='Вокзал', latitude=57.1911, longitude=39.4024),
          dict(id='kremlin', title='Кремль', latitude=57.1851, longitude=39.4164)]


def provider_route():
    middle = (57.188, 39.41)
    return {'trip': {'status': 0, 'summary': {'length': 1.381, 'time': 1018.01}, 'legs': [
        {'shape': encode([(57.1911, 39.4024), middle])},
        {'shape': encode([middle, (57.1851, 39.4164)])}]}}


def auth(client):
    token = client.post('/api/v1/auth/register', json={'username': 'walker', 'password': 'testing-password'}).json()['token']
    return {'Authorization': f'Bearer {token}'}


def test_real_provider_format_combines_legs_and_cache_does_not_reuse_private_titles(tmp_path):
    calls = []
    def handle(request):
        body = json.loads(request.content)
        assert request.url.path == '/route'
        assert body['costing'] == 'pedestrian'
        assert 'Вокзал' not in request.content.decode()
        assert request.headers['X-Client-Id'] == 'ru.trainster.app'
        calls.append(request)
        return httpx.Response(200, json=provider_route())
    app = create_app(Settings(database=tmp_path / 'walk.db'), provider_transport=httpx.MockTransport(handle))
    with TestClient(app) as client:
        headers = auth(client)
        route = client.post('/api/v1/walking/route', headers=headers, json={'points': POINTS})
        assert route.status_code == 200, route.text
        first = route.json()
        assert first['routing_source'] == 'valhalla_osm'
        assert first['distance_meters'] == 1381
        assert first['duration_seconds'] == 1018
        assert len(first['geometry']) == 3
        renamed = [{**point, 'title': 'Другое название', 'id': str(index)} for index, point in enumerate(POINTS)]
        second = client.post('/api/v1/walking/route', headers=headers, json={'points': renamed}).json()
        assert len(calls) == 1
        assert second['routed_at'] == first['routed_at']
        assert second['points'] == [{**point, 'stay_minutes': 0, 'address': None} for point in renamed]
        with app.state.database.connect() as db:
            cached = db.execute('SELECT route_json FROM walking_cache').fetchone()[0]
        assert 'Вокзал' not in cached and 'Другое название' not in cached and 'points' not in cached


@pytest.mark.parametrize('response,expected', [(httpx.Response(429), 503), (httpx.Response(400), 422),
    (httpx.Response(502), 502), (httpx.Response(200, json={'trip': {'legs': []}}), 502),
    (httpx.Response(200, json={'trip': {'legs': [{'shape': 'bad'}], 'summary': {'length': 1, 'time': 5}}}), 502)])
def test_provider_failure_never_fabricates_a_straight_path(tmp_path, response, expected):
    app = create_app(Settings(database=tmp_path / 'walk.db'), provider_transport=httpx.MockTransport(lambda _: response))
    with TestClient(app) as client:
        result = client.post('/api/v1/walking/route', headers=auth(client), json={'points': POINTS})
        assert result.status_code == expected
        assert 'geometry' not in result.json()


def test_limits_and_auth_checked_before_provider(tmp_path):
    def unexpected(_):
        pytest.fail('Invalid or unauthenticated request reached provider')
    app = create_app(Settings(database=tmp_path / 'walk.db'), provider_transport=httpx.MockTransport(unexpected))
    with TestClient(app) as client:
        assert client.post('/api/v1/walking/route', json={'points': POINTS}).status_code == 401
        headers = auth(client)
        for points in ([POINTS[0]], [POINTS[0]] * 26, [POINTS[0], POINTS[0]],
                       [POINTS[0], {**POINTS[1], 'latitude': 55.75, 'longitude': 37.62}]):
            assert client.post('/api/v1/walking/route', headers=headers, json={'points': points}).status_code == 422


def test_osm_path_survives_long_offline_storage():
    walk = dict(points=POINTS, geometry=[dict(latitude=57.1911, longitude=39.4024), dict(latitude=57.1851, longitude=39.4164)],
                distance_meters=1381, duration_seconds=1018, routed_at=(utcnow() - timedelta(days=180)).isoformat(), routing_source='valhalla_osm')
    raw = json.dumps({'blocks': [dict(walk=walk)]})
    cleaned, changed = prune_plan_json(raw)
    assert not changed
    assert cleaned == raw


@pytest.mark.parametrize('shape', ['', '_', '~~~~~~', '\x00\x00', '?'])
def test_truncated_and_invalid_polyline_rejected(shape):
    with pytest.raises(ValueError):
        decode_shape(shape)
