package ru.trainster.app

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiException(val status: Int, message: String) : IOException(message)

/** A token is encrypted with a non-exportable Android Keystore key. Passwords are never saved. */
private class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("trainster.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("trainster.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(server: String): LoginSession? = runCatching {
        if (prefs.getString("server", null) != server) return null
        val encrypted = Base64.decode(prefs.getString("session", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val j = JSONObject(String(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)), Charsets.UTF_8))
        LoginSession(j.getString("token"), Account.read(j.getJSONObject("user")))
    }.getOrNull()
    fun write(server: String, session: LoginSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = JSONObject().put("token", session.token).put("user", session.user.json()).toString().toByteArray()
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(data), Base64.NO_WRAP)
        prefs.edit().putString("server", server).putString("session", encrypted).apply()
    }
    fun clear() = prefs.edit().clear().apply()
}

class Repository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sessions = SessionStore(context)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    var server: String = prefs.getString("server", "http://10.0.2.2:8000")!!
        private set
    var session: LoginSession? = sessions.read(server)
        private set
    fun configure(url: String) {
        val parsed = url.trim().trimEnd('/').toHttpUrlOrNull() ?: throw IllegalArgumentException("Введите полный адрес сервера, например http://192.168.1.10:8000")
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null && parsed.encodedPath == "/") { "Укажите только адрес сервера с портом, без пути и пароля." }
        require(BuildConfig.DEBUG || parsed.isHttps) { "В релизной сборке сервер должен использовать HTTPS." }
        val normalized = parsed.toString().trimEnd('/')
        if (normalized != server) { server = normalized; session = null; sessions.clear(); prefs.edit().putString("server", server).apply() }
    }
    fun acceptSession(value: LoginSession) { sessions.write(server, value); session = value }
    fun clearSession() { session = null; sessions.clear() }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String {
        // Snapshot the endpoint and its token together before entering the IO dispatcher.
        val endpoint = server
        val token = session?.token
        return withContext(Dispatchers.IO) {
        val url = (endpoint + "/api/v1" + path).toHttpUrlOrNull()!!.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (method != "GET") builder.method(method, (body?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
        val activeClient = if (path == "/services/geometry") client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() else client
        activeClient.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw ApiException(response.code, detail ?: "Сервер вернул ошибку ${response.code}")
            }
            text
        }
        }
    }
    suspend fun auth(username: String, password: String, register: Boolean): LoginSession {
        val j = JSONObject(request(if (register) "/auth/register" else "/auth/login", "POST", JSONObject().put("username", username).put("password", password)))
        return LoginSession(j.getString("token"), Account.read(j.getJSONObject("user")))
    }
    suspend fun stations(query: String, mode: String = "suburban") = JSONArray(request("/stations", query = mapOf("q" to query, "mode" to mode))).objects(Station::read)
    suspend fun services(from: Station, to: Station, date: String, mode: String) = JSONArray(request("/services", query = mapOf("from_code" to from.code, "to_code" to to.code, "date" to date, "mode" to mode))).objects(Service::read)
    suspend fun completeService(service: Service, date: String): Service {
        val j = JSONObject(request("/services/thread", query = mapOf("uid" to service.uid, "date" to (service.serviceDate ?: date), "from_code" to service.fromStation.code, "to_code" to service.toStation.code)))
        return service.copy(stops = j.optJSONArray("stops").objects(Stop::read), geometry = j.optJSONArray("geometry").objects(GeoPoint::read), sourceUrl = j.optString("source_url", service.sourceUrl), fetchedAt = j.optString("fetched_at", service.fetchedAt))
    }
    suspend fun railGeometry(service: Service, date: String): Service {
        val j = JSONObject(request("/services/geometry", query = mapOf("uid" to service.uid, "date" to (service.serviceDate ?: date), "from_code" to service.fromStation.code, "to_code" to service.toStation.code)))
        return service.copy(geometry = j.optJSONArray("geometry").objects(GeoPoint::read), geometrySource = j.text("geometry_source"), geometryNote = j.optString("geometry_note"))
    }
    suspend fun searchPlaces(query: String, near: GeoPoint?): List<PlaceResult> = JSONArray(request("/places/search", "POST", JSONObject().put("query", query).value("near", near?.json()))).objects(PlaceResult::read)
    suspend fun routeWalk(points: List<WalkPoint>): Walk = Walk.read(JSONObject(request("/walking/route", "POST", JSONObject().put("points", points.json { it.json() }))))
    suspend fun participants(tripId: String): List<TripMember> = JSONArray(request("/trips/$tripId/participants")).objects(TripMember::read)
    suspend fun savePersonalization(tripId: String, userId: String, personalization: MemberPersonalization): TripMember =
        TripMember.read(JSONObject(request("/trips/$tripId/participants/$userId", "PUT", personalization.updateJson())))
    private fun cache(): AtomicFile? {
        val user = session?.user ?: return null
        val scope = MessageDigest.getInstance("SHA-256").digest("$server/${user.id}".toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir, "trips-$scope.json"))
    }
    fun readCache(): Triple<List<Trip>, Set<String>, String?> = runCatching {
        val j = JSONObject(String(cache()?.readFully() ?: return Triple(emptyList(), emptySet(), null), Charsets.UTF_8))
        val dirty = j.optJSONArray("dirty") ?: JSONArray()
        Triple(j.optJSONArray("trips").objects(Trip::read), (0 until dirty.length()).map { dirty.getString(it) }.toSet(), j.text("synced_at"))
    }.getOrElse { Triple(emptyList(), emptySet(), null) }
    fun writeCache(trips: List<Trip>, dirty: Set<String>, syncedAt: String?) {
        val file = cache() ?: return
        val data = JSONObject().put("trips", trips.json { it.json() }).put("dirty", JSONArray(dirty.toList())).value("synced_at", syncedAt).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

class TrainsterViewModel(application: Application) : AndroidViewModel(application) {
    val repository = Repository(application)
    var account by mutableStateOf(repository.session?.user); private set
    var trips by mutableStateOf(emptyList<Trip>()); private set
    var dirty by mutableStateOf(emptySet<String>()); private set
    var busy by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    var online by mutableStateOf(false); private set
    var lastSync by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var scheduleConfigured by mutableStateOf<Boolean?>(null); private set
    var selectedId by mutableStateOf<String?>(null)
    val selected: Trip? get() = trips.firstOrNull { it.id == selectedId }
    private var localGeneration = 0L
    init { loadCache() }
    private fun loadCache() {
        val cached = repository.readCache()
        trips = cached.first; dirty = cached.second; lastSync = cached.third; localGeneration++
        // Walk.read removes expired MapKit responses; also erase their bytes from disk offline.
        persist()
    }
    private fun persist() { runCatching { repository.writeCache(trips, dirty, lastSync) }.onFailure { message = "Не удалось сохранить локальную копию: ${it.message}" } }
    private fun explain(e: Exception): String = when(e) {
        is ApiException -> e.message ?: "Ошибка сервера"
        is IOException -> "Нет связи с сервером. Сохранённые путешествия доступны. Проверьте адрес сервера и подключение."
        else -> e.message ?: "Не удалось выполнить действие"
    }
    fun task(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch { busy = true; try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = explain(e) } finally { busy = false } }
    }
    fun setServer(value: String) {
        try {
            val before = repository.server; repository.configure(value)
            if (before != repository.server) { account = null; trips = emptyList(); dirty = emptySet(); selectedId = null; lastSync = null; scheduleConfigured = null; online = false; localGeneration++ }
            message = "Адрес сервера сохранён"
        } catch (e: Exception) { message = explain(e) }
    }
    fun login(username: String, password: String, register: Boolean) = task {
        val session = repository.auth(username.trim(), password, register)
        repository.acceptSession(session); account = session.user; loadCache(); sync()
    }
    fun logout() = task {
        runCatching { repository.request("/auth/logout", "POST") }
        repository.clearSession(); account = null; trips = emptyList(); dirty = emptySet(); selectedId = null; lastSync = null; scheduleConfigured = null; online = false; localGeneration++
    }
    suspend fun sync() {
        if (syncing || account == null) return
        val user = account?.id; val server = repository.server; val generation = localGeneration
        syncing = true
        try {
            val summaries = JSONArray(repository.request("/trips")).objects(Trip::read)
            val remote = mutableListOf<Trip>()
            for (summary in summaries) {
                remote += Trip.read(JSONObject(repository.request("/trips/${summary.id}")))
            }
            if (user != account?.id || server != repository.server || generation != localGeneration) return
            val remoteById = remote.associateBy { it.id }
            val localDirty = trips.filter { it.id in dirty }.map { local -> remoteById[local.id]?.let { mergePublished(local, it) } ?: local }
            trips = (remote.filterNot { it.id in dirty } + localDirty).sortedByDescending { it.updatedAt }
            lastSync = Instant.now().toString(); online = true; persist()
            runCatching { JSONObject(repository.request("/config")).getBoolean("schedule_configured") }.onSuccess { scheduleConfigured = it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (user == account?.id && server == repository.server) {
                online = false
                if (e is ApiException && e.status == 401) message = "Сессия истекла. Локальный план сохранён. Выйдите и войдите снова для синхронизации."
            }
        } finally { syncing = false }
    }
    fun refresh() = task { sync(); message = if (online) "Планы обновлены" else "Нет связи. Показываем локальную копию." }
    private fun put(trip: Trip) { localGeneration++; trips = (trips.filterNot { it.id == trip.id } + trip).sortedByDescending { it.updatedAt }; persist() }
    private fun mergePublished(local: Trip, remote: Trip) = local.copy(version = remote.version, status = remote.status, currentBlockId = remote.currentBlockId, publishedAt = remote.publishedAt, publishedBlocks = remote.publishedBlocks, hasUnpublishedChanges = true, personalization = remote.personalization)
    fun create(title: String, description: String) = task {
        val trip = Trip.read(JSONObject(repository.request("/trips", "POST", JSONObject().put("title", title).put("description", description))))
        put(trip); selectedId = trip.id
    }
    fun join(code: String) = task {
        val trip = Trip.read(JSONObject(repository.request("/trips/join", "POST", JSONObject().put("code", code.trim()))))
        put(trip); selectedId = trip.id
    }
    fun editTrip(trip: Trip) {
        if (trip.ownerId != account?.id) return
        dirty = dirty + trip.id; put(trip.copy(hasUnpublishedChanges = true))
    }
    fun addOrReplace(block: Block) {
        val trip = selected ?: return
        editTrip(trip.copy(blocks = if (trip.blocks.any { it.id == block.id }) trip.blocks.map { if (it.id == block.id) block else it } else trip.blocks + block))
    }
    fun moveBlock(id: String, delta: Int) {
        val trip = selected ?: return; val blocks = trip.blocks.toMutableList(); val index = blocks.indexOfFirst { it.id == id }; val target = index + delta
        if (index >= 0 && target in blocks.indices) { val block = blocks.removeAt(index); blocks.add(target, block); editTrip(trip.copy(blocks = blocks)) }
    }
    fun removeBlock(id: String) { selected?.let { editTrip(it.copy(blocks = it.blocks.filterNot { b -> b.id == id })) } }
    private suspend fun saveTrip(): Trip {
        val trip = selected ?: error("Путешествие не выбрано")
        if (trip.id !in dirty) return trip
        val body = JSONObject().put("title", trip.title).put("description", trip.description).put("blocks", trip.blocks.json { it.json() }).put("expected_revision", trip.draftRevision)
        val saved = Trip.read(JSONObject(repository.request("/trips/${trip.id}", "PUT", body)))
        dirty = dirty - saved.id; put(saved); online = true
        return saved
    }
    fun save() = task { saveTrip(); message = "Черновик сохранён в облаке" }
    fun publish() = task {
        val saved = saveTrip()
        val published = Trip.read(JSONObject(repository.request("/trips/${saved.id}/publish", "POST", JSONObject().put("expected_revision", saved.draftRevision))))
        put(published); message = "План опубликован. Участники получат версию ${published.version}."
    }
    fun cloudCopy(onResult: (Trip) -> Unit) = task {
        val trip = selected ?: return@task
        onResult(Trip.read(JSONObject(repository.request("/trips/${trip.id}"))))
    }
    fun replaceWithCloud(remote: Trip) { dirty = dirty - remote.id; put(remote); message = "Загружен облачный черновик" }
    fun keepLocalAgainst(remote: Trip) {
        val local = selected ?: return
        if (local.id != remote.id || local.ownerId != account?.id) return
        dirty = dirty + local.id
        put(mergePublished(local, remote).copy(draftRevision = remote.draftRevision))
        message = "Выбран локальный черновик. Следующее сохранение заменит облачный план; новые изменения на сервере снова вызовут конфликт."
    }
    fun state(status: String, blockId: String?) = task {
        val trip = selected ?: return@task
        val remote = Trip.read(JSONObject(repository.request("/trips/${trip.id}/state", "POST", JSONObject().put("status", status).value("current_block_id", blockId))))
        put(if (trip.id in dirty) mergePublished(trip, remote) else remote)
    }
}
