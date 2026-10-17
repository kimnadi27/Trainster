# Trainster MVP API contract

Base URL `/api/v1`; JSON snake_case; timestamps ISO 8601 with timezone; IDs strings (UUID). Auth: `Authorization: Bearer <opaque token>`. Errors `{ "detail": "human-readable Russian message" }`. No seeded/mock travel data. API description at `/docs`.

## Auth and configuration
- GET `/health` -> `{ "status":"ok" }` (outside `/api/v1`).
- GET `/api/v1/config` -> `{ "schedule_provider":"yandex", "schedule_configured":bool }`.
- POST `/api/v1/auth/register`, `/api/v1/auth/login` body `{ "username":str, "password":str }` -> `{ "token":str, "user": {"id":str,"username":str} }`.
- GET `/api/v1/auth/me` -> user; POST `/api/v1/auth/logout` -> 204.

## Trips
Trip summary/full share fields: `id`, `title`, `description`, `owner_id`, `invite_code`, `version` (published revision, initially 0), `draft_revision` (optimistic concurrency), `status` (`planning|active|finished`), `current_block_id` (nullable), `updated_at`, `blocks` (list, empty in summary allowed). Owner receives draft blocks from GET; members receive last published blocks. `published_at` nullable. Owner editing draft must not change published member plan. Trip is cached per account on Android.
Owner additionally receives `published_blocks` (published snapshot, empty before first publication) and `has_unpublished_changes`. Members receive empty `published_blocks` and false; their `blocks` already is the published plan. Execution view uses owner's published snapshot or member's published blocks, never owner's draft.
- GET `/trips` -> list of Trip.
- POST `/trips` body `{ "title":str,"description":str="" }` -> Trip.
- POST `/trips/join` body `{ "code":str }` -> published Trip (404 if unpublished).
- GET `/trips/{id}` -> Trip.
- PUT `/trips/{id}` body `{ "title":str, "description":str, "blocks":[Block], "expected_revision":int }` -> Trip (owner only; 409 conflict).
- POST `/trips/{id}/publish` body `{ "expected_revision":int }` -> Trip (owner only; reject empty/invalid plan; unchanged publication is a no-op).
- POST `/trips/{id}/state` body `{ "status":"planning|active|finished", "current_block_id":str|null }` -> Trip (owner only; active block must exist in published plan).

## Participants and individual plans

Every Trip has `personalization` for the requesting user only:

```json
{"revision": 0, "excluded_block_ids": [], "note": "", "block_notes": {}}
```

The common `blocks` and `current_block_id` remain intact. Android filters excluded blocks from the participant's plan and explains an excluded current stage. Personal data is cached with the participant's trip for offline use. The organizer's own personalization is empty; other participants' notes never appear in a Trip response.

- GET `/trips/{id}/participants` (owner only) -> `[{"user":{"id":str,"username":str},"personalization":Personalization}]`; joined members only, excluding the owner.
- PUT `/trips/{id}/participants/{user_id}` (owner only) body `{"expected_revision":int,"excluded_block_ids":[UUID],"note":str,"block_notes":{block_uuid:str}}` -> the same participant object.
- Participant must already have joined. Exclusions and note keys must reference the published plan. Up to 250 unique excluded IDs / note keys; each note up to 10000 characters. Unknown references -> 422; stale revision -> 409. Only changed values increase the individual revision.
- These edits apply immediately without publishing a draft, do not increase the common `version`, and notify only the affected participant. A later publication removes references to deleted blocks and increases affected individual revisions; general notes remain.
- Member attempts to list/edit participant configurations -> 403; outsiders see 404. Authentication is required.

## Change events (auth required)

- GET `/events` -> `{"events":[],"cursor":latest_global_id,"has_more":false,"reset":false}`. This is the initial bootstrap, without historic notifications.
- GET `/events?after=ID&limit=50` -> `{ "events":[Event],"cursor":int,"has_more":bool,"reset":bool }`; `after>=0`, limit 1–100.
- Event: `{ "id":int,"trip_id":str,"trip_title":str,"kind":"published|state_changed|participant_joined|personalization_changed","actor_id":str,"target_user_id":str|null,"message":str,"created_at":timestamp }`.
- The feed includes only trips owned/joined by the caller. Members see events created after joining; a new join does not replay earlier publications. Targeted events are visible only to the organizer and affected participant. Event messages contain no private note contents. Clients may filter their own `actor_id` to avoid notifying about their own actions.
- With `has_more=true`, cursor is the last returned event ID; fetch the next page before waiting. Otherwise cursor is the latest global ID, including when no visible events were returned. Persist the cursor per account and server. If `after` exceeds the current maximum (e.g. a database was replaced), return no events, latest cursor and `reset=true`.
- Events are committed atomically with publication, state changes, first joins and personal changes. Draft saves and unchanged repeated publish/state/join/personalization requests do not create events. Events survive server restarts.
- The feed is pull-based. It does not itself deliver FCM or guarantee immediate background Android notifications; delivery timing depends on the client polling and Android scheduling.

## Blocks
`id`: UUID, `kind`: `transport|walk|pause`, `title`:str, `starts_at`:str|null, `ends_at`:str|null, `notes`:str, `transport`:Transport|null, `walk`:Walk|null. Ordered list is plan order. Server validates timestamps, unique IDs, kinds and references. Overlap/connection checks may be warnings client-side.

Transport: `{ "mode":"suburban|train|bus", "from_station":Station, "to_station":Station, "service":Service }`.
Station: `{ "code":str,"title":str,"latitude":float|null,"longitude":float|null }`.
Service: `{ "uid":str,"number":str,"title":str,"departure":str,"arrival":str,"duration_seconds":int,"from_station":Station,"to_station":Station,"stops":[Stop],"geometry":[Point],"source_url":str,"fetched_at":str,"service_date":date|null }`. Optional service_date preserves originating train date (`start_date`) for overnight services; prefer it for thread/geometry requests.
Stop: `{ "station":Station, "arrival":str|null, "departure":str|null, "stop_seconds":int|null }`.
Point: `{ "latitude":float,"longitude":float }`.
Geometry must never fabricate rail shape by claiming straight station connections are actual railway. Empty geometry is valid and map must explain that exact rail geometry is unavailable.

Walk: `{ "points":[WalkPoint], "geometry":[Point], "distance_meters":float|null, "duration_seconds":int|null, "routed_at":str|null, "routing_source":"yandex_mapkit|valhalla_osm" }`. Missing source means legacy MapKit: its response cache expires after 30 days. OSM route geometry is retained for offline use. Preserve authored waypoints; publishing requires a calculated route.
WalkPoint: `{ "id":str,"title":str,"latitude":float,"longitude":float,"stay_minutes":int=0,"address":str|null }`. Address is optional for older plans and manual coordinates; up to 1500 characters.
Walking geometry is obtained from real Valhalla pedestrian routing through FastAPI and persisted with the plan. Do not pretend un-routed points form a calculated walk.

## Schedules (auth required)
- GET `/stations?q=Мытищи&mode=suburban|train|bus` (mode defaults to `suburban`; buses return bus stops, rail modes return railway stations) -> list Station. Server uses real cached Yandex station directory, provider config failure HTTP 503.
- GET `/services?from_code=...&to_code=...&date=YYYY-MM-DD&mode=suburban|train|bus` -> list Service, use actual dated direct services; no fake prices.
- GET `/services/thread?uid=...&date=YYYY-MM-DD&from_code=...&to_code=...` -> `{ "stops":[Stop], "geometry":[Point], "source_url":str, "fetched_at":str }`.
- GET `/services/geometry?uid=...&date=YYYY-MM-DD&from_code=...&to_code=...` -> `{ "geometry":[Point], "geometry_source":str|null, "geometry_note":str }`. On-demand OSM railway graph trace through dated service stops, best effort, bounded regional routes. Source `derived_osm` is a path along mapped railways, **not** the confirmed operational path of a particular train. No straight-line fallback. Empty geometry with explanatory note if insufficient data. Android may persist optional `geometry_source` and `geometry_note` in Service and must display attribution to OpenStreetMap contributors.
- Bus schedules and stop times use the same search/thread API with `transport_types=bus`. Bus road geometry is not returned or substituted with railway geometry; `/services/geometry` returns an empty geometry and explanation for buses. Coverage depends on Yandex's available routes.
- Attribute real provider. Do not claim this API provides real-time delays. Schedule credentials stay in server `.env`. MapLibre / OpenFreeMap need no key.

## Walking route (auth required)

- POST `/walking/route` body `{ "points":[WalkPoint] }` -> Walk, `routing_source=valhalla_osm`, timestamp of original calculation even on cache hit.
- 2–25 unique point IDs, valid finite coordinates, at most 50 km cumulative straight-line distance; 422 if invalid or no pedestrian route. Provider unavailability/rate limit -> 503, malformed response -> 502. No fabricated geometry fallback.
- Real provider coordinates/metrics are cached 7 days (maximum 128 entries), caller's titles/IDs/stay minutes are reattached. Public demo network requests are spaced at least 1 s apart per process. `TRAINSTER_WALK_ROUTING_URL` selects own Valhalla.
- Map packs are device-local Native OfflineManager regions, separate from trip JSON and API. All pack states, bytes and deletion are managed on Android.

## Places (auth required)

- POST `/places/search` body `{ "query":str, "near":Point|null }` -> up to 6 `{ "id":str, "title":str, "address":str, "latitude":float, "longitude":float, "source":"photon_osm" }` results.
- Query is trimmed, whitespace collapsed, length 3–200. Optional `near` is a previous plan waypoint, coarsened to 3 decimals; it biases ranking without excluding other cities. Device GPS is not used.
- Real Photon GeoJSON uses `[longitude, latitude]`; invalid coordinates are discarded, empty results return `[]`. All malformed results or upstream errors -> 502; provider timeout/429 -> 503. No placeholder places or guessed coordinates.
- User-triggered search only, 1 network request/s per process, cache 7 days / 256 entries keyed by a hash. `TRAINSTER_PLACE_SEARCH_URL` switches compatible Photon instances without an app update. No new API key.
