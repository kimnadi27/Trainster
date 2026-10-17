from datetime import date, datetime
from enum import Enum
import re
import unicodedata
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


def require_timezone(value: datetime | None):
    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError("Дата и время должны содержать часовой пояс")
    return value


class Credentials(Model):
    username: str = Field(min_length=3, max_length=40)
    password: str = Field(min_length=8, max_length=128)

    @field_validator("username")
    @classmethod
    def normalize_username(cls, value):
        value = unicodedata.normalize("NFKC", value.strip()).casefold()
        if not re.fullmatch(r"[\w.-]{3,40}", value):
            raise ValueError("Логин: 3–40 букв, цифр или символов _ . -")
        return value


class User(Model):
    id: str
    username: str


class AuthResponse(Model):
    token: str
    user: User


class Point(Model):
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)


class Station(Model):
    code: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=300)
    latitude: float | None = Field(default=None, ge=-90, le=90, allow_inf_nan=False)
    longitude: float | None = Field(default=None, ge=-180, le=180, allow_inf_nan=False)


class Stop(Model):
    station: Station
    arrival: datetime | None = None
    departure: datetime | None = None
    stop_seconds: int | None = Field(default=None, ge=0)

    _tz = field_validator("arrival", "departure")(require_timezone)


class Service(Model):
    uid: str = Field(min_length=1, max_length=300)
    number: str = Field(default="", max_length=100)
    title: str = Field(default="", max_length=500)
    service_date: date | None = None
    departure: datetime
    arrival: datetime
    duration_seconds: int = Field(ge=0)
    from_station: Station
    to_station: Station
    stops: list[Stop] = Field(default_factory=list, max_length=2000)
    geometry: list[Point] = Field(default_factory=list, max_length=50000)
    geometry_source: str | None = Field(default=None, max_length=100)
    geometry_note: str | None = Field(default=None, max_length=2000)
    source_url: str = Field(default="https://rasp.yandex.ru", max_length=2000)
    fetched_at: datetime

    _tz = field_validator("departure", "arrival", "fetched_at")(require_timezone)

    @model_validator(mode="after")
    def validate_service(self):
        if self.arrival < self.departure:
            raise ValueError("Прибытие не может быть раньше отправления")
        if self.from_station.code == self.to_station.code:
            raise ValueError("Станции отправления и прибытия должны отличаться")
        return self


class Transport(Model):
    mode: Literal["suburban", "train", "bus"]
    from_station: Station
    to_station: Station
    service: Service

    @model_validator(mode="after")
    def check_stations(self):
        if (self.from_station.code != self.service.from_station.code
                or self.to_station.code != self.service.to_station.code):
            raise ValueError("Выбранный рейс должен соответствовать станциям этапа")
        return self


class WalkPoint(Point):
    id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=300)
    stay_minutes: int = Field(default=0, ge=0, le=1440)
    address: str | None = Field(default=None, max_length=1500)


class Walk(Model):
    points: list[WalkPoint] = Field(default_factory=list, max_length=100)
    geometry: list[Point] = Field(default_factory=list, max_length=50000)
    distance_meters: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    duration_seconds: int | None = Field(default=None, ge=0)
    routed_at: datetime | None = None
    routing_source: Literal["yandex_mapkit", "valhalla_osm"] = "yandex_mapkit"

    _tz = field_validator("routed_at")(require_timezone)

    @model_validator(mode="after")
    def check_ids(self):
        if len({point.id for point in self.points}) != len(self.points):
            raise ValueError("Точки прогулки должны иметь разные идентификаторы")
        return self


class PlaceSearchRequest(Model):
    query: str = Field(min_length=3, max_length=200)
    near: Point | None = None

    @field_validator("query", mode="before")
    @classmethod
    def clean_query(cls, value):
        return " ".join(value.split()) if isinstance(value, str) else value


class PlaceSearchResult(Point):
    id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=300)
    address: str = Field(min_length=1, max_length=1500)
    source: Literal["photon_osm"] = "photon_osm"


class WalkingRouteRequest(Model):
    points: list[WalkPoint] = Field(min_length=2, max_length=25)


class Block(Model):
    id: UUID
    kind: Literal["transport", "walk", "pause"]
    title: str = Field(min_length=1, max_length=300)
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    notes: str = Field(default="", max_length=10000)
    transport: Transport | None = None
    walk: Walk | None = None

    _tz = field_validator("starts_at", "ends_at")(require_timezone)

    @model_validator(mode="after")
    def check_structure(self):
        if not self.title.strip():
            raise ValueError("У этапа должно быть название")
        if self.starts_at and self.ends_at and self.ends_at < self.starts_at:
            raise ValueError("Окончание этапа не может быть раньше начала")
        if self.kind == "transport":
            if self.transport is None or self.walk is not None:
                raise ValueError("Для передвижения необходим рейс")
            if (self.starts_at != self.transport.service.departure
                    or self.ends_at != self.transport.service.arrival):
                raise ValueError("Время этапа должно совпадать с выбранным рейсом")
        elif self.kind == "walk":
            if self.walk is None or self.transport is not None:
                raise ValueError("Для прогулки необходим маршрут")
        elif self.walk is not None or self.transport is not None:
            raise ValueError("У паузы не может быть маршрута или рейса")
        return self


class CreateTrip(Model):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=10000)

    @field_validator("title")
    @classmethod
    def clean_title(cls, value):
        if not value.strip():
            raise ValueError("У путешествия должно быть название")
        return value.strip()


class UpdateTrip(CreateTrip):
    blocks: list[Block] = Field(max_length=250)
    expected_revision: int = Field(ge=0)

    @field_validator("blocks")
    @classmethod
    def unique_blocks(cls, value):
        if len({block.id for block in value}) != len(value):
            raise ValueError("Этапы должны иметь разные идентификаторы")
        return value


class PublishTrip(Model):
    expected_revision: int = Field(ge=0)


class JoinTrip(Model):
    code: str = Field(min_length=1, max_length=30)


class TripStatus(str, Enum):
    PLANNING = "planning"
    ACTIVE = "active"
    FINISHED = "finished"


class TripState(Model):
    status: TripStatus
    current_block_id: UUID | None = None


class PersonalizationConfig(Model):
    excluded_block_ids: list[UUID] = Field(default_factory=list, max_length=250)
    note: str = Field(default="", max_length=10000)
    block_notes: dict[UUID, str] = Field(default_factory=dict, max_length=250)

    @field_validator("excluded_block_ids")
    @classmethod
    def unique_exclusions(cls, value):
        if len(set(value)) != len(value):
            raise ValueError("Этапы не должны повторяться")
        return value

    @field_validator("block_notes")
    @classmethod
    def bounded_notes(cls, value):
        if any(len(note) > 10000 for note in value.values()):
            raise ValueError("Заметка к этапу слишком длинная")
        return value


class Personalization(PersonalizationConfig):
    revision: int = Field(default=0, ge=0)


class UpdatePersonalization(PersonalizationConfig):
    expected_revision: int = Field(ge=0)


class TripParticipant(Model):
    user: User
    personalization: Personalization


class TripEvent(Model):
    id: int
    trip_id: str
    trip_title: str
    kind: Literal["published", "state_changed", "participant_joined", "personalization_changed"]
    actor_id: str
    target_user_id: str | None
    message: str
    created_at: datetime


class EventPage(Model):
    events: list[TripEvent]
    cursor: int
    has_more: bool
    reset: bool = False


class Trip(Model):
    id: str
    title: str
    description: str
    owner_id: str
    invite_code: str
    version: int
    draft_revision: int
    status: TripStatus
    current_block_id: str | None
    updated_at: datetime
    blocks: list[Block]
    published_blocks: list[Block] = Field(default_factory=list)
    has_unpublished_changes: bool = False
    published_at: datetime | None
    personalization: Personalization = Field(default_factory=Personalization)


class ThreadDetails(Model):
    stops: list[Stop]
    geometry: list[Point] = Field(default_factory=list)
    source_url: str
    fetched_at: datetime
    geometry_source: str | None = None
    geometry_note: str | None = None


class GeometryDetails(Model):
    geometry: list[Point]
    geometry_source: str | None = None
    geometry_note: str | None = None
