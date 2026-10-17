from contextlib import asynccontextmanager
from datetime import date
import json
import secrets
import sqlite3
from typing import Annotated, Literal
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import httpx

from .config import Settings
from .cache_policy import prune_plan_json, prune_trip_row
from .database import Database, transaction
from .models import (AuthResponse, CreateTrip, Credentials, GeometryDetails, JoinTrip,
                     PublishTrip, Service, Station, ThreadDetails, Trip, TripState,
                     TripStatus, UpdateTrip, User, Walk, WalkingRouteRequest, PlaceSearchRequest, PlaceSearchResult,
                     EventPage, TripParticipant, UpdatePersonalization)
from .provider import BUS_NO_GEOMETRY, YandexRasp
from .walking import WalkingRouter
from .geocoding import PlaceSearch
from .trip_changes import append_event, event_page, latest_cursor, prune_personalizations, read_personalization
from .security import (DUMMY_PASSWORD_HASH, create_session, hash_password, token_hash,
                       utcnow, verify_password)


bearer = HTTPBearer(auto_error=False)


def create_app(settings: Settings | None = None, *, provider_transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    database = Database(settings.database)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        database.initialize()
        async with httpx.AsyncClient(transport=provider_transport, follow_redirects=False) as client:
            app.state.provider = YandexRasp(settings, database, client)
            app.state.walking = WalkingRouter(settings, database, client)
            app.state.places = PlaceSearch(settings, database, client)
            yield

    app = FastAPI(title="Trainster", version="0.4.0", lifespan=lifespan)
    app.state.settings = settings
    app.state.database = database

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError):
        # Never echo request inputs: validation failures may contain passwords.
        fields = "; ".join(".".join(str(part) for part in item["loc"][1:]) for item in error.errors()[:5])
        return JSONResponse(status_code=422, content={"detail": f"Некорректные данные: {fields or 'проверьте запрос'}."})

    def connection():
        with database.connect() as db:
            yield db

    def current_user(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
                     db: Annotated[sqlite3.Connection, Depends(connection)]):
        if not credentials or len(credentials.credentials) > 256:
            raise HTTPException(401, "Войдите в аккаунт.", headers={"WWW-Authenticate": "Bearer"})
        row = db.execute(
            "SELECT users.id,users.username FROM sessions JOIN users ON users.id=sessions.user_id "
            "WHERE token_hash=? AND expires_at>?", (token_hash(credentials.credentials), utcnow().isoformat()),
        ).fetchone()
        if not row:
            raise HTTPException(401, "Сессия истекла. Войдите снова.", headers={"WWW-Authenticate": "Bearer"})
        return User(**dict(row))

    DB = Annotated[sqlite3.Connection, Depends(connection)]
    Auth = Annotated[User, Depends(current_user)]

    def accessible_trip(db, trip_id, user_id):
        row = db.execute("SELECT * FROM trips WHERE id=?", (trip_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Путешествие не найдено.")
        if row["owner_id"] != user_id:
            member = db.execute("SELECT 1 FROM memberships WHERE trip_id=? AND user_id=?", (trip_id, user_id)).fetchone()
            if not member or row["published_json"] is None:
                raise HTTPException(404, "Путешествие не найдено.")
        return prune_trip_row(db, row)

    def require_owner(row, user_id):
        if row["owner_id"] != user_id:
            raise HTTPException(403, "Изменять путешествие может только организатор.")

    def trip_response(row, user_id, db):
        is_owner = row["owner_id"] == user_id
        published_plan = json.loads(row["published_json"]) if row["published_json"] else None
        plan = json.loads(row["draft_json"]) if is_owner else published_plan
        return Trip(
            id=row["id"], owner_id=row["owner_id"], invite_code=row["invite_code"],
            version=row["version"], draft_revision=row["draft_revision"], status=row["status"],
            current_block_id=row["current_block_id"], updated_at=row["updated_at"],
            published_blocks=published_plan["blocks"] if is_owner and published_plan else [],
            has_unpublished_changes=(published_plan is None or json.loads(row["draft_json"]) != published_plan) if is_owner else False,
            published_at=row["published_at"], personalization=read_personalization(db, row["id"], user_id), **plan,
        )

    def ensure_revision(row, expected):
        if row["draft_revision"] != expected:
            raise HTTPException(409, "План уже изменён в другом сеансе. Обновите путешествие перед сохранением.")

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/api/v1/config")
    def config():
        return {"schedule_provider": "yandex", "schedule_configured": bool(settings.yandex_rasp_api_key)}

    @app.post("/api/v1/auth/register", response_model=AuthResponse, status_code=201)
    def register(body: Credentials, db: DB):
        user = User(id=str(uuid4()), username=body.username)
        password_hash = hash_password(body.password)
        try:
            with transaction(db):
                db.execute("INSERT INTO users(id,username,password_hash,created_at) VALUES (?,?,?,?)",
                           (user.id, user.username, password_hash, utcnow().isoformat()))
                token = create_session(db, user.id, settings.session_days)
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Этот логин уже занят.") from None
        return AuthResponse(token=token, user=user)

    @app.post("/api/v1/auth/login", response_model=AuthResponse)
    def login(body: Credentials, db: DB):
        row = db.execute("SELECT * FROM users WHERE username=?", (body.username,)).fetchone()
        verified = verify_password(body.password, row["password_hash"] if row else DUMMY_PASSWORD_HASH)
        if not row or not verified:
            raise HTTPException(401, "Неверный логин или пароль.")
        with transaction(db):
            token = create_session(db, row["id"], settings.session_days)
        return AuthResponse(token=token, user=User(id=row["id"], username=row["username"]))

    @app.get("/api/v1/auth/me", response_model=User)
    def me(user: Auth):
        return user

    @app.post("/api/v1/auth/logout", status_code=204)
    def logout(user: Auth, db: DB, credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)]):
        db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash(credentials.credentials),))
        return Response(status_code=204)

    @app.get("/api/v1/trips", response_model=list[Trip])
    def list_trips(user: Auth, db: DB):
        rows = db.execute(
            "SELECT trips.* FROM trips WHERE owner_id=? OR (published_json IS NOT NULL AND id IN "
            "(SELECT trip_id FROM memberships WHERE user_id=?)) ORDER BY updated_at DESC", (user.id, user.id),
        ).fetchall()
        return [trip_response(prune_trip_row(db, row), user.id, db) for row in rows]

    @app.post("/api/v1/trips", response_model=Trip, status_code=201)
    def create_trip(body: CreateTrip, user: Auth, db: DB):
        trip_id = str(uuid4())
        invite_code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(10))
        plan = {**body.model_dump(), "blocks": []}
        with transaction(db):
            db.execute("INSERT INTO trips(id,owner_id,invite_code,draft_json,updated_at) VALUES (?,?,?,?,?)",
                       (trip_id, user.id, invite_code, json.dumps(plan, ensure_ascii=False), utcnow().isoformat()))
        return trip_response(accessible_trip(db, trip_id, user.id), user.id, db)

    @app.post("/api/v1/trips/join", response_model=Trip)
    def join_trip(body: JoinTrip, user: Auth, db: DB):
        code = body.code.strip().upper()
        with transaction(db):
            row = db.execute("SELECT * FROM trips WHERE invite_code=? AND published_json IS NOT NULL", (code,)).fetchone()
            if not row:
                raise HTTPException(404, "Опубликованное путешествие с таким кодом не найдено.")
            if row["owner_id"] != user.id:
                joined = db.execute("INSERT OR IGNORE INTO memberships(trip_id,user_id,event_after_id) VALUES (?,?,?)",
                                    (row["id"], user.id, latest_cursor(db)))
                if joined.rowcount:
                    title = json.loads(row["published_json"])["title"]
                    append_event(db, row["id"], title, "participant_joined", user.id,
                                 f"{user.username} присоединился к путешествию.", target_user_id=user.id)
        return trip_response(prune_trip_row(db, row), user.id, db)

    @app.get("/api/v1/trips/{trip_id}", response_model=Trip)
    def get_trip(trip_id: str, user: Auth, db: DB):
        return trip_response(accessible_trip(db, trip_id, user.id), user.id, db)

    @app.put("/api/v1/trips/{trip_id}", response_model=Trip)
    def update_trip(trip_id: str, body: UpdateTrip, user: Auth, db: DB):
        with transaction(db):
            row = accessible_trip(db, trip_id, user.id)
            require_owner(row, user.id)
            ensure_revision(row, body.expected_revision)
            plan = body.model_dump(mode="json", exclude={"expected_revision"})
            raw_plan, _ = prune_plan_json(json.dumps(plan, ensure_ascii=False))
            db.execute("UPDATE trips SET draft_json=?,draft_revision=draft_revision+1,updated_at=? WHERE id=?",
                       (raw_plan, utcnow().isoformat(), trip_id))
        return trip_response(accessible_trip(db, trip_id, user.id), user.id, db)

    @app.post("/api/v1/trips/{trip_id}/publish", response_model=Trip)
    def publish_trip(trip_id: str, body: PublishTrip, user: Auth, db: DB):
        with transaction(db):
            row = accessible_trip(db, trip_id, user.id)
            require_owner(row, user.id)
            ensure_revision(row, body.expected_revision)
            plan = json.loads(row["draft_json"])
            if not plan["blocks"]:
                raise HTTPException(422, "Добавьте хотя бы один этап перед публикацией.")
            for block in plan["blocks"]:
                if block["kind"] == "walk" and (len(block["walk"]["points"]) < 2 or len(block["walk"]["geometry"]) < 2):
                    raise HTTPException(422, "Постройте пешеходный маршрут между точками прогулки перед публикацией.")
            block_ids = {block["id"] for block in plan["blocks"]}
            current_block = row["current_block_id"]
            if current_block and current_block not in block_ids:
                if row["status"] == "active":
                    raise HTTPException(409, "Текущий этап удалён из черновика. Переведите поездку в планирование перед публикацией.")
                current_block = None
            if row["published_json"] and plan == json.loads(row["published_json"]):
                return trip_response(row, user.id, db)
            now = utcnow().isoformat()
            db.execute("UPDATE trips SET published_json=draft_json,version=version+1,published_at=?,updated_at=?,current_block_id=? WHERE id=?",
                       (now, now, current_block, trip_id))
            prune_personalizations(db, trip_id, block_ids)
            append_event(db, trip_id, plan["title"], "published", user.id,
                         "Организатор опубликовал обновлённый план путешествия.")
        return trip_response(accessible_trip(db, trip_id, user.id), user.id, db)

    @app.post("/api/v1/trips/{trip_id}/state", response_model=Trip)
    def change_state(trip_id: str, body: TripState, user: Auth, db: DB):
        with transaction(db):
            row = accessible_trip(db, trip_id, user.id)
            require_owner(row, user.id)
            if row["published_json"] is None:
                raise HTTPException(409, "Сначала опубликуйте путешествие.")
            block_ids = {block["id"] for block in json.loads(row["published_json"])["blocks"]}
            current_block = str(body.current_block_id) if body.current_block_id else None
            if body.status == TripStatus.ACTIVE and not current_block:
                raise HTTPException(422, "Выберите текущий этап опубликованного плана.")
            if current_block and current_block not in block_ids:
                raise HTTPException(422, "Текущий этап должен существовать в опубликованном плане.")
            if row["status"] == body.status.value and row["current_block_id"] == current_block:
                return trip_response(row, user.id, db)
            db.execute("UPDATE trips SET status=?,current_block_id=?,updated_at=? WHERE id=?",
                       (body.status.value, current_block, utcnow().isoformat(), trip_id))
            plan = json.loads(row["published_json"])
            if body.status == TripStatus.ACTIVE:
                stage = next(block["title"] for block in plan["blocks"] if block["id"] == current_block)
                message = ("Путешествие началось. " if row["status"] != "active" else "Текущий этап изменён. ") + stage
            elif body.status == TripStatus.FINISHED:
                message = "Организатор завершил путешествие."
            else:
                message = "Путешествие возвращено в планирование."
            append_event(db, trip_id, plan["title"], "state_changed", user.id, message)
        return trip_response(accessible_trip(db, trip_id, user.id), user.id, db)

    @app.get("/api/v1/trips/{trip_id}/participants", response_model=list[TripParticipant])
    def participants(trip_id: str, user: Auth, db: DB):
        row = accessible_trip(db, trip_id, user.id)
        require_owner(row, user.id)
        members = db.execute(
            "SELECT users.id,users.username FROM memberships JOIN users ON users.id=memberships.user_id "
            "WHERE trip_id=? ORDER BY users.username", (trip_id,),
        ).fetchall()
        return [TripParticipant(user=User(**dict(member)),
                                personalization=read_personalization(db, trip_id, member["id"])) for member in members]

    @app.put("/api/v1/trips/{trip_id}/participants/{participant_id}", response_model=TripParticipant)
    def update_participant(trip_id: str, participant_id: str, body: UpdatePersonalization, user: Auth, db: DB):
        with transaction(db):
            row = accessible_trip(db, trip_id, user.id)
            require_owner(row, user.id)
            if not row["published_json"]:
                raise HTTPException(409, "Сначала опубликуйте общий план путешествия.")
            member = db.execute(
                "SELECT users.id,users.username FROM memberships JOIN users ON users.id=memberships.user_id "
                "WHERE trip_id=? AND user_id=?", (trip_id, participant_id),
            ).fetchone()
            if not member:
                raise HTTPException(404, "Участник не найден в этом путешествии.")
            old = read_personalization(db, trip_id, participant_id)
            if old.revision != body.expected_revision:
                raise HTTPException(409, "Индивидуальный план уже изменён. Обновите список участников.")
            plan = json.loads(row["published_json"])
            block_ids = {block["id"] for block in plan["blocks"]}
            config = body.model_dump(mode="json", exclude={"expected_revision"})
            if (set(config["excluded_block_ids"]) | set(config["block_notes"])) - block_ids:
                raise HTTPException(422, "Индивидуальный план может ссылаться только на опубликованные этапы.")
            if config != old.model_dump(mode="json", exclude={"revision"}):
                db.execute(
                    "INSERT INTO personalizations(trip_id,user_id,revision,config_json) VALUES (?,?,?,?) "
                    "ON CONFLICT(trip_id,user_id) DO UPDATE SET revision=excluded.revision,config_json=excluded.config_json",
                    (trip_id, participant_id, old.revision + 1, json.dumps(config, ensure_ascii=False)),
                )
                db.execute("UPDATE trips SET updated_at=? WHERE id=?", (utcnow().isoformat(), trip_id))
                append_event(db, trip_id, plan["title"], "personalization_changed", user.id,
                             "Организатор изменил ваш индивидуальный план или личные заметки.", target_user_id=participant_id)
            return TripParticipant(user=User(**dict(member)), personalization=read_personalization(db, trip_id, participant_id))

    @app.get("/api/v1/events", response_model=EventPage)
    def events(user: Auth, db: DB, after: Annotated[int | None, Query(ge=0)] = None,
               limit: Annotated[int, Query(ge=1, le=100)] = 50):
        return event_page(db, user.id, after, limit)

    def provider(request: Request) -> YandexRasp:
        return request.app.state.provider

    Provider = Annotated[YandexRasp, Depends(provider)]

    @app.get("/api/v1/stations", response_model=list[Station])
    async def stations(user: Auth, source: Provider, q: Annotated[str, Query(min_length=2, max_length=100)],
                       mode: Literal["suburban", "train", "bus"] = "suburban"):
        return await source.search_stations(q, mode)

    @app.get("/api/v1/services", response_model=list[Service])
    async def services(user: Auth, source: Provider, from_code: Annotated[str, Query(min_length=1, max_length=128)],
                       to_code: Annotated[str, Query(min_length=1, max_length=128)], date: date,
                       mode: Literal["suburban", "train", "bus"] = "suburban"):
        if from_code == to_code:
            raise HTTPException(422, "Выберите разные станции отправления и прибытия.")
        return await source.services(from_code, to_code, date, mode)

    @app.get("/api/v1/services/thread", response_model=ThreadDetails)
    async def thread(user: Auth, source: Provider, uid: Annotated[str, Query(min_length=1, max_length=300)], date: date,
                     from_code: Annotated[str, Query(min_length=1, max_length=128)],
                     to_code: Annotated[str, Query(min_length=1, max_length=128)]):
        return await source.thread(uid, date, from_code, to_code)

    @app.get("/api/v1/services/geometry", response_model=GeometryDetails)
    async def geometry(user: Auth, source: Provider, uid: Annotated[str, Query(min_length=1, max_length=300)], date: date,
                       from_code: Annotated[str, Query(min_length=1, max_length=128)],
                       to_code: Annotated[str, Query(min_length=1, max_length=128)]):
        from .rail_geometry import trace
        details = await source.thread(uid, date, from_code, to_code)
        if details.geometry_note == BUS_NO_GEOMETRY:
            return GeometryDetails(geometry=[], geometry_note=BUS_NO_GEOMETRY)
        return await trace([stop.station.model_dump() for stop in details.stops])

    @app.post("/api/v1/walking/route", response_model=Walk)
    async def walking_route(body: WalkingRouteRequest, user: Auth, request: Request):
        return await request.app.state.walking.route(body.points)

    @app.post("/api/v1/places/search", response_model=list[PlaceSearchResult])
    async def places_search(body: PlaceSearchRequest, user: Auth, request: Request):
        return await request.app.state.places.search(body.query, body.near)

    return app


app = create_app()
