import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx

from app.rail_geometry import RailGeometryProvider, trace_graph


def station(latitude, longitude):
    return {"code": str((latitude, longitude)), "title": "Station", "latitude": latitude, "longitude": longitude}


def way(ids, coordinates, **tags):
    return {"type": "way", "id": ids[0], "nodes": ids,
            "geometry": [{"lat": lat, "lon": lon} for lat, lon in coordinates],
            "tags": {"railway": "rail", **tags}}


class RailGraphTests(unittest.TestCase):
    def test_follows_curved_track_and_stops_in_requested_order(self):
        elements = [way([1, 2, 3, 4], [(56, 38), (56.02, 38.02), (56, 38.04), (56, 38.06)])]
        result = trace_graph([station(56, 38), station(56.02, 38.02), station(56, 38.06)], elements)
        self.assertEqual(result["geometry_source"], "derived_osm")
        self.assertIn({"latitude": 56.02, "longitude": 38.02}, result["geometry"])
        self.assertIn("фактического", result["geometry_note"])
        reverse = trace_graph([station(56, 38.06), station(56.02, 38.02), station(56, 38)], elements)
        self.assertEqual(reverse["geometry"], list(reversed(result["geometry"])))

    def test_snaps_to_edge_between_distant_osm_nodes(self):
        elements = [way([1, 2], [(56, 38), (56, 38.1)])]
        result = trace_graph([station(56, 38.03), station(56, 38.07)], elements)
        self.assertEqual(result["geometry_source"], "derived_osm")
        self.assertAlmostEqual(result["geometry"][0]["longitude"], 38.03)
        self.assertAlmostEqual(result["geometry"][-1]["longitude"], 38.07)

    def test_intermediate_stop_forces_branch_instead_of_shorter_track(self):
        elements = [
            way([1, 2, 3], [(56, 38), (56, 38.03), (56, 38.06)]),
            way([1, 4, 3], [(56, 38), (56.04, 38.03), (56, 38.06)]),
        ]
        result = trace_graph([station(56, 38), station(56.04, 38.03), station(56, 38.06)], elements)
        self.assertIn({"latitude": 56.04, "longitude": 38.03}, result["geometry"])
        self.assertNotIn({"latitude": 56, "longitude": 38.03}, result["geometry"])

    def test_nearest_disconnected_branch_does_not_hide_connected_alternative(self):
        elements = [
            way([1, 2, 3], [(56, 38), (56, 38.05), (56, 38.1)]),
            way(list(range(4, 14)), [(56.001, 38.0495 + index * 0.0001) for index in range(10)]),
        ]
        result = trace_graph([station(56, 38), station(56.001, 38.05), station(56, 38.1)], elements)
        self.assertEqual(result["geometry_source"], "derived_osm")
        self.assertTrue(all(point["latitude"] == 56 for point in result["geometry"]))

    def test_disconnected_tracks_return_no_invented_bridge(self):
        elements = [way([1, 2], [(56, 38), (56, 38.02)]),
                    way([3, 4], [(56, 38.1), (56, 38.12)])]
        result = trace_graph([station(56, 38), station(56, 38.12)], elements)
        self.assertEqual(result["geometry"], [])
        self.assertIsNone(result["geometry_source"])
        self.assertIn("связный", result["geometry_note"])

    def test_missing_intermediate_is_reported_and_missing_endpoint_rejected(self):
        elements = [way([1, 2], [(56, 38), (56, 38.06)])]
        middle = station(None, None)
        result = trace_graph([station(56, 38), middle, station(56, 38.06)], elements)
        self.assertEqual(result["geometry_source"], "derived_osm")
        self.assertIn("1 промежуточных", result["geometry_note"])
        result = trace_graph([middle, station(56, 38.06)], elements)
        self.assertEqual(result["geometry"], [])

    def test_does_not_snap_unrelated_station_far_from_rail(self):
        elements = [way([1, 2], [(56, 38), (56, 38.1)])]
        result = trace_graph([station(56, 38), station(56.1, 38.1)], elements)
        self.assertEqual(result["geometry"], [])

    def test_excludes_yard_and_requires_real_rail_ways(self):
        result = trace_graph([station(56, 38), station(56, 38.1)],
                             [way([1, 2], [(56, 38), (56, 38.1)], service="yard")])
        self.assertEqual(result["geometry"], [])

    def test_malformed_provider_elements_are_ignored_without_inventing_path(self):
        malformed = [None, {"type": "way", "tags": None},
                     {"type": "way", "tags": {"railway": "rail"}, "nodes": None},
                     {"type": "way", "tags": {"railway": "rail"}, "nodes": [1], "geometry": [None]}]
        result = trace_graph([station(56, 38), station(56, 38.1)], malformed)
        self.assertEqual(result["geometry"], [])


class RailProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_disk_cache_prunes_old_entries_to_bound(self):
        result = {"geometry": [{"latitude": 56, "longitude": 38}, {"latitude": 56, "longitude": 38.1}],
                  "geometry_source": "derived_osm", "geometry_note": "OSM"}
        with tempfile.TemporaryDirectory() as directory:
            provider = RailGeometryProvider(cache_dir=Path(directory))
            with patch("app.rail_geometry.MAX_CACHE_FILES", 2):
                for key in ("first", "second", "third"):
                    provider._save(key, result)
            self.assertEqual(len(list(Path(directory).glob("*.json"))), 2)
            self.assertEqual(list(Path(directory).glob("*.tmp")), [])

    async def test_oversized_region_is_rejected_before_any_query(self):
        calls = []

        def handler(request):
            calls.append(request)
            raise AssertionError("Oversized routes must not query Overpass")

        provider = RailGeometryProvider(transport=httpx.MockTransport(handler))
        result = await provider.trace([station(55, 37), station(65, 60)])
        self.assertEqual(calls, [])
        self.assertEqual(result["geometry"], [])
        self.assertIn("500 км", result["geometry_note"])

    async def test_real_query_contract_and_successful_disk_cache(self):
        calls = []
        payload = {"elements": [way([1, 2, 3], [(56, 38), (56.01, 38.03), (56, 38.06)])]}

        def handler(request):
            calls.append(request)
            self.assertEqual(request.method, "POST")
            self.assertIn(b"railway", request.content)
            self.assertIn(b"out+geom", request.content)
            return httpx.Response(200, json=payload)

        with tempfile.TemporaryDirectory() as directory:
            provider = RailGeometryProvider(cache_dir=Path(directory), transport=httpx.MockTransport(handler))
            stops = [station(56, 38), station(56, 38.06)]
            first = await provider.trace(stops)
            second = await provider.trace(stops)
            self.assertEqual(first["geometry_source"], "derived_osm")
            self.assertEqual(first, second)
            self.assertEqual(len(calls), 1)
            self.assertEqual(len(list(Path(directory).glob("*.json"))), 1)

    async def test_provider_http_error_and_partial_response_never_make_geometry(self):
        for response in (httpx.Response(429), httpx.Response(200, json={"remark": "timeout", "elements": []})):
            provider = RailGeometryProvider(transport=httpx.MockTransport(lambda request: response))
            result = await provider.trace([station(56, 38), station(56, 38.1)])
            self.assertEqual(result["geometry"], [])
            self.assertIsNone(result["geometry_source"])

    async def test_expired_disk_cache_is_refetched(self):
        import os
        import time

        calls = []
        payload = {"elements": [way([1, 2], [(56, 38), (56, 38.1)])]}

        def handler(request):
            calls.append(request)
            return httpx.Response(200, json=payload)

        with tempfile.TemporaryDirectory() as directory:
            provider = RailGeometryProvider(cache_dir=Path(directory), cache_hours=1,
                                            transport=httpx.MockTransport(handler))
            stops = [station(56, 38), station(56, 38.1)]
            await provider.trace(stops)
            path = next(Path(directory).glob("*.json"))
            os.utime(path, (time.time() - 7200, time.time() - 7200))
            await provider.trace(stops)
            self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
