package ru.trainster.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Conservative allowance beneath OpenFreeMap's 3000-resource / 50 MB limits. */
private const val MAX_RESOURCES = 2700L
private const val MAX_BYTES = 45_000_000L
const val OFFLINE_MAX_ZOOM = 14.0

fun estimatedOfflineTiles(bounds: LatLngBounds): Long {
    if (bounds.longitudeEast > 180 || bounds.longitudeWest < -180 || bounds.longitudeEast <= bounds.longitudeWest || bounds.latitudeSpan <= 0 || bounds.longitudeSpan > 180) return Long.MAX_VALUE
    var count = 0L
    for (zoom in 0..OFFLINE_MAX_ZOOM.toInt()) {
        val n = 1 shl zoom
        fun x(lon: Double) = floor((lon + 180) / 360 * n).toLong().coerceIn(0, n.toLong() - 1)
        fun y(lat: Double): Long {
            val radians = Math.toRadians(lat.coerceIn(-85.051128, 85.051128))
            return floor((1 - asinh(tan(radians)) / PI) / 2 * n).toLong().coerceIn(0, n.toLong() - 1)
        }
        val tiles = (x(bounds.longitudeEast) - x(bounds.longitudeWest) + 1) * (y(bounds.latitudeSouth) - y(bounds.latitudeNorth) + 1)
        count += tiles * if (zoom <= 6) 2 else 1 // vector source + low resolution relief
    }
    return count
}

private class TransferBudget(initialBytes: Long, val onLimit: () -> Unit) {
    private var bytes = initialBytes
    private var requests = 0L
    private var stopped = false
    @Synchronized fun add(size: Long = 0, request: Boolean = false) {
        if (stopped) throw IOException("Offline area limit")
        bytes += size
        if (request) requests++
        if (bytes > MAX_BYTES || requests > MAX_RESOURCES) {
            stopped = true
            onLimit()
            throw IOException("Offline area limit")
        }
    }
}

object MapRuntime {
    @Volatile private var budget: TransferBudget? = null
    fun initialize(context: Context) {
        MapLibre.getInstance(context)
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val client = OkHttpClient.Builder().dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val transfer = budget
                transfer?.add(request = true)
                val response = chain.proceed(chain.request().newBuilder().header("User-Agent", "Trainster/0.2 (ru.trainster.app)").build())
                val body = response.body
                if (transfer == null || body == null) response else response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    private val counted by lazy {
                        object : ForwardingSource(body.source()) {
                            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) transfer.add(it) }
                        }.buffer()
                    }
                    override fun source(): BufferedSource = counted
                }).build()
            }.build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        OfflineMaps.initialize(context)
    }
    fun beginDownload(bytes: Long, onLimit: () -> Unit) { budget = TransferBudget(bytes, onLimit) }
    fun endDownload() { budget = null }
}

data class DownloadedArea(
    val id: Long, val name: String, val bounds: LatLngBounds, val bytes: Long = 0,
    val completed: Long = 0, val required: Long = 0, val precise: Boolean = false,
    val ready: Boolean = false, val active: Boolean = false, val error: String? = null, val limited: Boolean = false
)

/** Native SQLite packs contain the style, fonts, sprites and both tile sources. */
object OfflineMaps {
    val areas = mutableStateListOf<DownloadedArea>()
    var loading by mutableStateOf(true); private set
    var message by mutableStateOf<String?>(null)
    private lateinit var manager: OfflineManager
    private lateinit var preferences: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val regions = mutableMapOf<Long, OfflineRegion>()
    private var activeId: Long? = null
    private var creating = false

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("offline-map-limits", Context.MODE_PRIVATE)
        manager = OfflineManager.getInstance(context)
        reload()
    }
    fun reload() {
        if (creating || activeId != null) return
        loading = true
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions.clear(); areas.clear()
                offlineRegions.orEmpty().forEach { attach(it) }
                loading = false
            }
            override fun onError(error: String) { loading = false; message = "Не удалось прочитать загрузки. Попробуйте снова." }
        })
    }
    private fun attach(region: OfflineRegion) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        val definition = region.definition as? OfflineTilePyramidRegionDefinition ?: return
        val bounds = definition.bounds ?: return
        val metadata = runCatching { JSONObject(String(region.metadata, Charsets.UTF_8)) }.getOrNull()
        regions[region.id] = region
        areas.add(DownloadedArea(region.id, metadata?.optString("name") ?: "Сохранённая область", bounds,
            limited = preferences.getBoolean("limited-${region.id}", false)))
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) { update(region, status) }
            override fun onError(error: OfflineRegionError) {
                handler.post {
                    pause(region.id)
                    change(region.id) { it.copy(error = "Загрузка прервана. Проверьте интернет и свободное место.") }
                }
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) { limit(region.id) }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { status?.let { update(region, it) } }
            override fun onError(error: String?) { change(region.id) { it.copy(error = "Не удалось проверить загрузку.") } }
        })
    }
    private fun change(id: Long, transform: (DownloadedArea) -> DownloadedArea) {
        val index = areas.indexOfFirst { it.id == id }
        if (index >= 0) areas[index] = transform(areas[index])
    }
    private fun update(region: OfflineRegion, status: OfflineRegionStatus) {
        handler.post {
            if (!regions.containsKey(region.id)) return@post
            val overLimit = status.completedResourceSize > MAX_BYTES || status.requiredResourceCount > MAX_RESOURCES
            if (overLimit) limit(region.id)
            change(region.id) { it.copy(bytes = status.completedResourceSize, completed = status.completedResourceCount,
                required = status.requiredResourceCount, precise = status.isRequiredResourceCountPrecise,
                ready = status.isComplete && !overLimit && !it.limited, active = activeId == region.id && !status.isComplete) }
            if (status.isComplete && activeId == region.id) pause(region.id)
        }
    }
    private fun limit(id: Long) {
        handler.post {
            pause(id)
            preferences.edit().putBoolean("limited-$id", true).apply()
            change(id) { it.copy(active = false, ready = false, limited = true, error = "Область достигла лимита. Удалите её и выберите меньший участок.") }
        }
    }
    fun download(bounds: LatLngBounds, pixelRatio: Float) {
        if (creating || loading) return
        if (estimatedOfflineTiles(bounds) > 2000) { message = "Приблизьте карту и выберите меньшую область — например центр города."; return }
        if (activeId != null) { message = "Сначала дождитесь текущей загрузки или поставьте её на паузу."; return }
        message = null
        creating = true
        val center = bounds.center
        val name = "Область %.2f, %.2f · %s".format(java.util.Locale.ROOT, center.latitude, center.longitude,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))
        val metadata = JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8)
        val definition = OfflineTilePyramidRegionDefinition(BuildConfig.MAP_STYLE_URL, bounds, 0.0, OFFLINE_MAX_ZOOM, pixelRatio)
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) { creating = false; attach(offlineRegion); resume(offlineRegion.id) }
            override fun onError(error: String) { creating = false; message = "Не удалось создать загрузку. Проверьте свободное место." }
        })
    }
    fun resume(id: Long) {
        if (activeId != null && activeId != id) { message = "Одновременно скачивается одна область."; return }
        val area = areas.firstOrNull { it.id == id } ?: return
        if (area.ready || area.limited) return
        message = null
        activeId = id
        change(id) { it.copy(active = true, error = null) }
        MapRuntime.beginDownload(area.bytes) { limit(id) }
        regions[id]?.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }
    fun pause(id: Long) {
        regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        if (activeId == id) { activeId = null; MapRuntime.endDownload() }
        change(id) { it.copy(active = false) }
    }
    fun delete(id: Long) {
        pause(id)
        regions[id]?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { regions.remove(id); areas.removeAll { it.id == id }; preferences.edit().remove("limited-$id").apply() }
            override fun onError(error: String) { message = "Не удалось удалить область. Попробуйте снова." }
        })
    }
}
