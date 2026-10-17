package ru.trainster.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
fun JSONObject.number(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }
fun <T> JSONArray?.objects(read: (JSONObject) -> T): List<T> = if (this == null) emptyList() else (0 until length()).map { read(getJSONObject(it)) }
fun <T> List<T>.json(write: (T) -> JSONObject): JSONArray = JSONArray().also { a -> forEach { a.put(write(it)) } }
fun JSONObject.value(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
fun newId(): String = UUID.randomUUID().toString()

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun json() = JSONObject().put("latitude", latitude).put("longitude", longitude)
    companion object { fun read(j: JSONObject) = GeoPoint(j.getDouble("latitude"), j.getDouble("longitude")) }
}
data class Station(val code: String, val title: String, val latitude: Double? = null, val longitude: Double? = null) {
    val point: GeoPoint? get() = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
    fun json() = JSONObject().put("code", code).put("title", title).value("latitude", latitude).value("longitude", longitude)
    companion object { fun read(j: JSONObject) = Station(j.getString("code"), j.getString("title"), j.number("latitude"), j.number("longitude")) }
}
data class Stop(val station: Station, val arrival: String?, val departure: String?, val stopSeconds: Int?) {
    fun json() = JSONObject().put("station", station.json()).value("arrival", arrival).value("departure", departure).value("stop_seconds", stopSeconds)
    companion object { fun read(j: JSONObject) = Stop(Station.read(j.getJSONObject("station")), j.text("arrival"), j.text("departure"), j.number("stop_seconds")?.toInt()) }
}
data class Service(
    val uid: String, val number: String, val title: String, val departure: String, val arrival: String,
    val durationSeconds: Int, val fromStation: Station, val toStation: Station,
    val stops: List<Stop> = emptyList(), val geometry: List<GeoPoint> = emptyList(),
    val sourceUrl: String = "", val fetchedAt: String = "", val geometrySource: String? = null, val geometryNote: String = "", val serviceDate: String? = null
) {
    fun json() = JSONObject().put("uid", uid).put("number", number).put("title", title).put("departure", departure)
        .put("arrival", arrival).put("duration_seconds", durationSeconds).put("from_station", fromStation.json())
        .put("to_station", toStation.json()).put("stops", stops.json { it.json() }).put("geometry", geometry.json { it.json() })
        .put("source_url", sourceUrl).put("fetched_at", fetchedAt).value("geometry_source", geometrySource).put("geometry_note", geometryNote).value("service_date", serviceDate)
    companion object {
        fun read(j: JSONObject) = Service(j.getString("uid"), j.optString("number"), j.optString("title"), j.getString("departure"),
            j.getString("arrival"), j.optInt("duration_seconds"), Station.read(j.getJSONObject("from_station")), Station.read(j.getJSONObject("to_station")),
            j.optJSONArray("stops").objects(Stop::read), j.optJSONArray("geometry").objects(GeoPoint::read), j.optString("source_url"), j.optString("fetched_at"), j.text("geometry_source"), j.optString("geometry_note"), j.text("service_date"))
    }
}
data class Transport(val mode: String, val fromStation: Station, val toStation: Station, val service: Service) {
    fun json() = JSONObject().put("mode", mode).put("from_station", fromStation.json()).put("to_station", toStation.json()).put("service", service.json())
    companion object { fun read(j: JSONObject) = Transport(j.getString("mode"), Station.read(j.getJSONObject("from_station")), Station.read(j.getJSONObject("to_station")), Service.read(j.getJSONObject("service"))) }
}
data class WalkPoint(val id: String = newId(), val title: String, val latitude: Double, val longitude: Double, val stayMinutes: Int = 0, val address: String? = null) {
    val point get() = GeoPoint(latitude, longitude)
    fun json() = JSONObject().put("id", id).put("title", title).put("latitude", latitude).put("longitude", longitude).put("stay_minutes", stayMinutes).value("address", address)
    companion object { fun read(j: JSONObject) = WalkPoint(j.getString("id"), j.optString("title"), j.getDouble("latitude"), j.getDouble("longitude"), j.optInt("stay_minutes"), j.text("address")) }
}
data class PlaceResult(val id: String, val title: String, val address: String, val latitude: Double, val longitude: Double) {
    val point get() = GeoPoint(latitude, longitude)
    fun walkPoint() = WalkPoint(title = title, latitude = latitude, longitude = longitude, address = address)
    companion object { fun read(j: JSONObject) = PlaceResult(j.getString("id"), j.getString("title"), j.getString("address"), j.getDouble("latitude"), j.getDouble("longitude")) }
}

data class Walk(val points: List<WalkPoint>, val geometry: List<GeoPoint> = emptyList(), val distanceMeters: Double? = null, val durationSeconds: Int? = null, val routedAt: String? = null, val routingSource: String = "yandex_mapkit") {
    val routeExpired: Boolean get() = routingSource == "yandex_mapkit" && routedAt != null && (instant(routedAt)?.isAfter(Instant.now().minusSeconds(30L * 24 * 3600)) != true)
    val availableGeometry get() = if (routeExpired || routedAt == null) emptyList() else geometry
    fun json() = JSONObject().put("points", points.json { it.json() }).put("geometry", availableGeometry.json { it.json() }).value("distance_meters", if (availableGeometry.isEmpty()) null else distanceMeters).value("duration_seconds", if (availableGeometry.isEmpty()) null else durationSeconds).value("routed_at", routedAt).put("routing_source", routingSource)
    companion object { fun read(j: JSONObject): Walk {
        val walk = Walk(j.optJSONArray("points").objects(WalkPoint::read), j.optJSONArray("geometry").objects(GeoPoint::read), j.number("distance_meters"), j.number("duration_seconds")?.toInt(), j.text("routed_at"), j.optString("routing_source", "yandex_mapkit"))
        return if (walk.routeExpired || walk.routedAt == null) walk.copy(geometry = emptyList(), distanceMeters = null, durationSeconds = null) else walk
    } }
}
data class Block(
    val id: String = newId(), val kind: String, val title: String, val startsAt: String? = null, val endsAt: String? = null,
    val notes: String = "", val transport: Transport? = null, val walk: Walk? = null
) {
    fun json() = JSONObject().put("id", id).put("kind", kind).put("title", title).value("starts_at", startsAt).value("ends_at", endsAt)
        .put("notes", notes).value("transport", transport?.json()).value("walk", walk?.json())
    val kindName get() = when(kind) { "transport" -> when (transport?.mode) { "train" -> "Поезд"; "bus" -> "Автобус"; else -> "Электричка" }; "walk" -> "Прогулка"; else -> "Пауза / ночёвка" }
    companion object { fun read(j: JSONObject) = Block(j.getString("id"), j.getString("kind"), j.getString("title"), j.text("starts_at"), j.text("ends_at"), j.optString("notes"), j.optJSONObject("transport")?.let(Transport::read), j.optJSONObject("walk")?.let(Walk::read)) }
}
/** Only the signed-in member's plan is included in a downloaded Trip. */
data class MemberPersonalization(
    val revision: Int = 0, val excludedBlockIds: Set<String> = emptySet(),
    val note: String = "", val blockNotes: Map<String, String> = emptyMap()
) {
    fun json() = JSONObject().put("revision", revision).put("excluded_block_ids", JSONArray(excludedBlockIds.toList()))
        .put("note", note).put("block_notes", JSONObject(blockNotes))
    fun updateJson() = json().apply { remove("revision"); put("expected_revision", revision) }
    companion object {
        fun read(j: JSONObject?): MemberPersonalization {
            if (j == null) return MemberPersonalization()
            val excluded = j.optJSONArray("excluded_block_ids") ?: JSONArray()
            val notes = j.optJSONObject("block_notes") ?: JSONObject()
            return MemberPersonalization(j.optInt("revision"), (0 until excluded.length()).map { excluded.getString(it) }.toSet(),
                j.optString("note"), notes.keys().asSequence().associateWith { notes.optString(it) })
        }
    }
}
data class TripMember(val user: Account, val personalization: MemberPersonalization) {
    companion object { fun read(j: JSONObject) = TripMember(Account.read(j.getJSONObject("user")), MemberPersonalization.read(j.optJSONObject("personalization"))) }
}

data class Trip(
    val id: String, val title: String, val description: String, val ownerId: String, val inviteCode: String,
    val version: Int, val draftRevision: Int, val status: String, val currentBlockId: String?, val updatedAt: String,
    val blocks: List<Block>, val publishedAt: String? = null, val publishedBlocks: List<Block>? = null, val hasUnpublishedChanges: Boolean = false,
    val personalization: MemberPersonalization = MemberPersonalization()
) {
    val executionBlocks: List<Block> get() = publishedBlocks?.takeIf { it.isNotEmpty() } ?: if (version > 0) blocks else emptyList()
    val personalBlocks: List<Block> get() = blocks.filter { participatesIn(it.id) }
    val personalExecutionBlocks: List<Block> get() = executionBlocks.filter { participatesIn(it.id) }
    fun participatesIn(blockId: String): Boolean = blockId !in personalization.excludedBlockIds
    fun personalNoteFor(blockId: String): String = personalization.blockNotes[blockId].orEmpty()
    fun json() = JSONObject().put("id", id).put("title", title).put("description", description).put("owner_id", ownerId).put("invite_code", inviteCode)
        .put("version", version).put("draft_revision", draftRevision).put("status", status).value("current_block_id", currentBlockId).put("updated_at", updatedAt)
        .put("blocks", blocks.json { it.json() }).value("published_at", publishedAt).value("published_blocks", publishedBlocks?.json { it.json() }).put("has_unpublished_changes", hasUnpublishedChanges).put("personalization", personalization.json())
    companion object { fun read(j: JSONObject) = Trip(j.getString("id"), j.getString("title"), j.optString("description"), j.getString("owner_id"), j.optString("invite_code"), j.optInt("version"), j.optInt("draft_revision"), j.optString("status", "planning"), j.text("current_block_id"), j.optString("updated_at"), j.optJSONArray("blocks").objects(Block::read), j.text("published_at"), j.optJSONArray("published_blocks")?.objects(Block::read), j.optBoolean("has_unpublished_changes"), MemberPersonalization.read(j.optJSONObject("personalization"))) }
}
data class Account(val id: String, val username: String) {
    fun json() = JSONObject().put("id", id).put("username", username)
    companion object { fun read(j: JSONObject) = Account(j.getString("id"), j.getString("username")) }
}
data class LoginSession(val token: String, val user: Account)

fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
fun planWarnings(blocks: List<Block>): List<String> = blocks.zipWithNext().mapNotNull { (a, b) ->
    val end = instant(a.endsAt); val start = instant(b.startsAt)
    when {
        end != null && start != null && start < end -> "«${b.title}» начинается до окончания «${a.title}»."
        a.transport != null && b.transport != null && a.transport.toStation.code != b.transport.fromStation.code -> "Между «${a.title}» и «${b.title}» разные станции. Добавьте переход."
        end != null && start != null && b.kind == "transport" && java.time.Duration.between(end, start).toMinutes() < 10 -> "До «${b.title}» менее 10 минут. Проверьте пересадку."
        else -> null
    }
}
