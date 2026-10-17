from datetime import timedelta
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import httpx
import pytest

from app.config import Settings
from app.geocoding import parse_places
from app.main import create_app
from app.security import utcnow


def feature(name='Мэрия Москвы', lat=55.7577, lon=37.6138, osm_id=123):
    return {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [lon, lat]},
            'properties': {'name': name, 'osm_type': 'W', 'osm_id': osm_id, 'street': 'Тверская улица',
                           'housenumber': '13', 'city': 'Москва', 'state': 'Москва', 'country': 'Россия'}}


def auth(client):
    session = client.post('/api/v1/auth/register', json={'username': 'place-tester', 'password': 'isolated-test-password'}).json()
    return {'Authorization': f"Bearer {session['token']}"}


def test_photon_coordinates_address_and_invalid_results():
    valid = feature()
    nameless = feature(name='', osm_id=456)
    places = parse_places({'features': [valid, valid, feature(lat=150), nameless]})
    assert len(places) == 2
    assert places[0].latitude == 55.7577 and places[0].longitude == 37.6138
    assert places[0].address == 'Тверская улица 13, Москва, Россия'
    assert places[0].source == 'photon_osm'
    assert places[1].title == 'Тверская улица 13'


def test_authenticated_search_caches_normalized_queries_and_preserves_focus(tmp_path):
    calls = []
    def handler(request):
        assert request.url.params['lang'] == 'default'  # public demo rejects lang=ru
        assert request.url.params['q'] == 'Москва Тверская 13'
        assert request.url.params['lat'] == '55.758'
        assert request.url.params['lon'] == '37.614'
        assert request.headers['User-Agent'].startswith('Trainster/')
        assert 'Authorization' not in request.headers
        calls.append(request)
        return httpx.Response(200, json={'features': [feature()]})
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        headers = auth(client)
        body = {'query': ' Москва   Тверская 13 ', 'near': {'latitude': 55.7577, 'longitude': 37.6138}}
        response = client.post('/api/v1/places/search', json=body, headers=headers)
        assert response.status_code == 200, response.text
        assert response.json()[0]['title'] == 'Мэрия Москвы'
        body['query'] = 'москва тверская 13'
        assert client.post('/api/v1/places/search', json=body, headers=headers).json() == response.json()
        assert len(calls) == 1
        with app.state.database.connect() as db:
            row = db.execute('SELECT * FROM place_search_cache').fetchone()
        assert len(row['cache_key']) == 64 and 'query' not in row['results_json']


@pytest.mark.parametrize('reply,status', [(httpx.Response(429), 503), (httpx.Response(500), 502),
    (httpx.Response(200, json={'unexpected': 'format'}), 502),
    (httpx.Response(200, json={'features': [feature(lat=999)]}), 502)])
def test_provider_failure_has_no_invented_results(tmp_path, reply, status):
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(lambda _: reply))
    with TestClient(app) as client:
        response = client.post('/api/v1/places/search', json={'query': 'Ростов кремль'}, headers=auth(client))
        assert response.status_code == status
        assert 'latitude' not in response.json()


def test_empty_results_are_valid_and_cached(tmp_path):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={'features': []})
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        headers = auth(client)
        for _ in range(2):
            response = client.post('/api/v1/places/search', json={'query': 'Неизвестное место'}, headers=headers)
            assert response.status_code == 200 and response.json() == []
        assert len(calls) == 1


def test_authorization_and_query_limits_before_network(tmp_path):
    def forbidden(_):
        pytest.fail('Invalid request reached Photon')
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(forbidden))
    with TestClient(app) as client:
        assert client.post('/api/v1/places/search', json={'query': 'Москва'}).status_code == 401
        headers = auth(client)
        for query in ('  ', 'аб', 'я' * 201):
            assert client.post('/api/v1/places/search', json={'query': query}, headers=headers).status_code == 422
        assert client.post('/api/v1/places/search', json={'query': 'Москва', 'near': {'latitude': 120, 'longitude': 37}}, headers=headers).status_code == 422


def test_expired_cache_refreshed(tmp_path):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={'features': [feature(name=f'Место {len(calls)}')]})
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        headers = auth(client)
        first = client.post('/api/v1/places/search', json={'query': 'Москва'}, headers=headers).json()
        with app.state.database.connect() as db:
            db.execute('UPDATE place_search_cache SET updated_at=?', ((utcnow() - timedelta(days=8)).isoformat(),))
        second = client.post('/api/v1/places/search', json={'query': 'Москва'}, headers=headers).json()
        assert first[0]['title'] != second[0]['title'] and len(calls) == 2


def test_address_survives_save_publish_and_reload(tmp_path):
    with TestClient(create_app(Settings(database=tmp_path / 'places.db'))) as client:
        headers = auth(client)
        trip = client.post('/api/v1/trips', json={'title': 'Прогулка'}, headers=headers).json()
        points = [dict(id='one', title='Мэрия', latitude=55.7577, longitude=37.6138, address='Тверская улица 13, Москва'),
                  dict(id='two', title='Сквер', latitude=55.758, longitude=37.614, address=None)]
        walk = {'points': points, 'geometry': [{'latitude': p['latitude'], 'longitude': p['longitude']} for p in points],
                'distance_meters': 30, 'duration_seconds': 25, 'routed_at': utcnow().isoformat(), 'routing_source': 'valhalla_osm'}
        block = {'id': str(uuid4()), 'kind': 'walk', 'title': 'Прогулка', 'walk': walk}
        saved = client.put(f"/api/v1/trips/{trip['id']}", json={'title': trip['title'], 'description': '', 'blocks': [block], 'expected_revision': 0}, headers=headers)
        assert saved.status_code == 200, saved.text
        assert client.post(f"/api/v1/trips/{trip['id']}/publish", json={'expected_revision': 1}, headers=headers).status_code == 200
        reloaded = client.get(f"/api/v1/trips/{trip['id']}", headers=headers).json()
        assert reloaded['blocks'][0]['walk']['points'][0]['address'] == points[0]['address']
        assert reloaded['published_blocks'][0]['walk']['points'][0]['address'] == points[0]['address']


def test_timeout_keeps_manual_map_selection_available(tmp_path):
    def handler(request):
        raise httpx.ReadTimeout('provider timed out', request=request)
    app = create_app(Settings(database=tmp_path / 'places.db'), provider_transport=httpx.MockTransport(handler))
    with TestClient(app) as client:
        response = client.post('/api/v1/places/search', json={'query': 'Ростов кремль'}, headers=auth(client))
        assert response.status_code == 503
        assert 'карте' in response.json()['detail']


def test_named_place_without_street_retains_original_location_description():
    item = feature(name='Соборная площадь')
    item['properties'].update(street='', housenumber='', city='Ростов Великий', state='Ярославская область')
    result = parse_places({'features': [item]})[0]
    assert result.address.startswith('Соборная площадь, Ростов Великий')
