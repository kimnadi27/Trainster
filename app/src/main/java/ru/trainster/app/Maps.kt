@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.trainster.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import java.time.Instant
import kotlin.math.ln

data class MapMarker(val point: GeoPoint, val label: String, val title: String, val own: Boolean = false, val address: String? = null)
data class MapLine(val points: List<GeoPoint>, val railway: Boolean)
private fun GeoPoint.latLng() = LatLng(latitude, longitude)

@android.annotation.SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(
    markers: List<MapMarker>, lines: List<MapLine>, modifier: Modifier = Modifier,
    initialPoint: GeoPoint? = null, focusPoint: GeoPoint? = null, onLongPress: ((GeoPoint) -> Unit)? = null,
    onTap: ((GeoPoint) -> Unit)? = null, fitContent: Boolean = true, showDownloadControls: Boolean = true
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) } }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember(mapView) { mutableStateOf(false) }
    var error by remember(mapView) { mutableStateOf<String?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    var proposedBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var selectedMarker by remember(mapView) { mutableStateOf<MapMarker?>(null) }
    val markerIndex = remember(mapView) { mutableMapOf<Long, MapMarker>() }
    val icons = remember(mapView) { mutableMapOf<Pair<String, Boolean>, Icon>() }
    val callback by rememberUpdatedState(onLongPress)
    val tapCallback by rememberUpdatedState(onTap)
    DisposableEffect(mapView, lifecycle) {
        var started = false; var resumed = false
        fun start() { if (!started) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit
        } }
        mapView.setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
            false // MapLibre handles taps, long presses and accessibility gestures.
        }
        mapView.addOnDidFailLoadingMapListener { error = "Карта не загрузилась. Проверьте интернет или откройте скачанную область." }
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.setOnMarkerClickListener { native ->
                markerIndex[native.id]?.let { marker ->
                    if (tapCallback != null) tapCallback?.invoke(marker.point) else selectedMarker = marker
                }
                true
            }
            loaded.addOnMapClickListener { point -> tapCallback?.invoke(GeoPoint(point.latitude, point.longitude)); tapCallback != null }
            loaded.addOnMapLongClickListener { point -> callback?.invoke(GeoPoint(point.latitude, point.longitude)); callback != null }
            loaded.setStyle(BuildConfig.MAP_STYLE_URL) { style -> localizeMap(style); styleReady = true; error = null }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop(); mapView.onDestroy() }
    }
    val contentPoints = if (fitContent) markers.filterNot { it.own }.map { it.point } + lines.flatMap { it.points } else emptyList()
    LaunchedEffect(map, contentPoints, initialPoint) {
        val points = contentPoints.ifEmpty { listOfNotNull(initialPoint) }
        val center = if (points.isEmpty()) GeoPoint(55.75, 37.62) else GeoPoint((points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2, (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2)
        val span = if (points.size < 2) 0.02 else maxOf(points.maxOf { it.latitude } - points.minOf { it.latitude }, points.maxOf { it.longitude } - points.minOf { it.longitude })
        val zoom = (ln(180.0 / maxOf(span, 0.005)) / ln(2.0)).coerceIn(3.0, 15.0)
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(center.latLng(), zoom))
    }
    LaunchedEffect(map, focusPoint) { focusPoint?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it.latLng(), 15.0)) } }
    LaunchedEffect(map, styleReady, markers, lines) {
        val current = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        current.clear()
        markerIndex.clear()
        selectedMarker = selectedMarker?.takeIf { it in markers }
        lines.filter { it.points.size >= 2 }.forEach { line ->
            current.addPolyline(PolylineOptions().addAll(line.points.map { it.latLng() }).width(4f)
                .color(if (line.railway) android.graphics.Color.rgb(201, 115, 40) else android.graphics.Color.rgb(18, 107, 98)))
        }
        markers.forEach { marker ->
            val icon = icons.getOrPut(marker.label to marker.own) { IconFactory.getInstance(context).fromBitmap(markerBitmap(context, marker.label, marker.own)) }
            val native = current.addMarker(MarkerOptions().position(marker.point.latLng()).title(marker.title).icon(icon))
            markerIndex[native.id] = marker
        }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (showDownloadControls) Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { map?.let { proposedBounds = it.projection.visibleRegion.latLngBounds } }, enabled = styleReady) { Text("Скачать область", fontSize = 11.sp) }
            FilledTonalButton(onClick = { showDownloads = true }) { Text("Загрузки", fontSize = 11.sp) }
        }
        if (!styleReady && error == null) Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text("Загружаем карту…")
            }
        }
        error?.let { text -> Surface(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 40.dp), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { error = null; styleReady = false; map?.setStyle(BuildConfig.MAP_STYLE_URL) { style -> localizeMap(style); styleReady = true } }) { Text("Повторить") }
            }
        } }
    }
    selectedMarker?.let { marker -> MapMarkerDialog(marker) { selectedMarker = null } }
    proposedBounds?.let { bounds ->
        val estimate = estimatedOfflineTiles(bounds)
        val fits = estimate <= 2000
        AlertDialog(onDismissRequest = { proposedBounds = null }, title = { Text("Скачать видимую область?") },
            text = { Text(if (fits) "Сохраним карту с улицами и подписями до масштаба города. На один участок можно загрузить до 45 МБ. Дождитесь статуса «Готово», прежде чем отключать интернет."
                else "Эта область слишком большая. Приблизьте карту и выберите меньший участок — например центр города. Города можно скачать отдельно.") },
            confirmButton = { TextButton(onClick = { OfflineMaps.download(bounds, mapView.pixelRatio); proposedBounds = null; showDownloads = true }, enabled = fits) { Text("Скачать") } },
            dismissButton = { TextButton(onClick = { proposedBounds = null }) { Text("Отмена") } })
    }
    if (showDownloads) ModalBottomSheet(onDismissRequest = { showDownloads = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Карты в дорогу", style = MaterialTheme.typography.headlineSmall)
            Text("Готовые области доступны после перезапуска без интернета. Загрузка продолжается, пока приложение открыто. После перезапуска незавершённые загрузки стоят на паузе.", style = MaterialTheme.typography.bodySmall)
            if (OfflineMaps.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (OfflineMaps.areas.isEmpty() && !OfflineMaps.loading) Text("Загрузок пока нет. Выберите участок на карте и нажмите «Скачать область».")
            OfflineMaps.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OfflineMaps.areas.forEach { area ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(area.name, style = MaterialTheme.typography.titleSmall)
                    Text(when { area.limited -> "Достигнут лимит · выберите меньшую область"; area.ready -> "Готово · доступно офлайн"; area.active -> "Скачиваем…"; else -> "Не завершено · на паузе" })
                    Text("%.1f МБ".format(area.bytes / 1_000_000.0) + if (!area.ready && area.precise && area.required > 0) " · ${(100 * area.completed / area.required).coerceIn(0L, 100L)}%" else "", style = MaterialTheme.typography.bodySmall)
                    if (area.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    area.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        if (area.ready) TextButton(onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngBounds(area.bounds, 32)); showDownloads = false }) { Text("Открыть") }
                        else if (!area.limited) TextButton(onClick = { if (area.active) OfflineMaps.pause(area.id) else OfflineMaps.resume(area.id) }) { Text(if (area.active) "Пауза" else "Продолжить") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { OfflineMaps.delete(area.id) }) { Text("Удалить") }
                    }
                } }
            }
            TextButton(onClick = { OfflineMaps.reload() }) { Text("Обновить список") }
            Text("Карты: OpenFreeMap / OpenMapTiles, © OpenStreetMap contributors. Путь: Valhalla / OSM. Новые пешеходные пути строятся при наличии интернета.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun localizeMap(style: Style) {
    // Preserve numbers and shield labels; localize only expressions using feature names.
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (layer.textField.expression?.toString()?.contains("name") == true) {
            layer.setProperties(PropertyFactory.textField(Expression.coalesce(Expression.get("name:ru"), Expression.get("name"), Expression.get("name:latin"))))
        }
    }
}

private fun markerBitmap(context: Context, text: String, own: Boolean): Bitmap {
    val intermediate = text == "•" && !own
    val size = ((if (intermediate) 12 else 36) * context.resources.displayMetrics.density).toInt().coerceAtLeast(12)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
        paint.color = if (own) android.graphics.Color.rgb(40, 110, 210) else android.graphics.Color.rgb(18, 107, 98)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.41f, paint)
        paint.color = android.graphics.Color.WHITE; paint.textSize = size * 0.4f; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
        if (!intermediate) canvas.drawText(text.take(3), size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

@Composable
fun TripMapScreen(trip: Trip) {
    var selected by remember(trip.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var own by remember { mutableStateOf<Location?>(null) }
    var locate by remember { mutableStateOf<GeoPoint?>(null) }
    var locationStatus by remember { mutableStateOf("Геопозиция показывается только на вашем устройстве.") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission = hasPermission(); if (!permission) locationStatus = "Разрешение не предоставлено. Маршрут доступен без геопозиции." }
    DisposableEffect(permission, lifecycle) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { own = location; locationStatus = "Ваше положение · точность ±${location.accuracy.toInt()} м · ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(location.time))}" }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { locationStatus = "Включите геопозицию в настройках устройства." }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        fun start() {
            if (!permission) return
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
            if (providers.isEmpty()) locationStatus = "Включите геопозицию в настройках устройства."
            providers.forEach { provider ->
                try {
                    manager.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { listener.onLocationChanged(it) }
                    manager.requestLocationUpdates(provider, 5_000, 5f, listener, Looper.getMainLooper())
                } catch (_: SecurityException) { locationStatus = "Нет разрешения на геопозицию." } catch (_: IllegalArgumentException) { }
            }
        }
        val observer = LifecycleEventObserver { _, event -> when(event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> manager.removeUpdates(listener); else -> Unit } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); manager.removeUpdates(listener) }
    }
    val personalBlocks = trip.personalBlocks
    LaunchedEffect(personalBlocks, selected) { if (selected != null && personalBlocks.none { it.id == selected }) selected = null }
    val blocks = if (selected == null) personalBlocks else personalBlocks.filter { it.id == selected }
    val markers = buildList {
        blocks.forEach { block ->
            block.transport?.let { transport ->
                val stations = transport.service.stops.map { it.station }.ifEmpty { listOf(transport.fromStation, transport.toStation) }.distinctBy { it.code }
                stations.forEach { station -> station.point?.let { add(MapMarker(it, if (station.code == transport.fromStation.code) "A" else if (station.code == transport.toStation.code) "Б" else "•", station.title)) } }
            }
            block.walk?.points?.forEachIndexed { index, point -> add(MapMarker(point.point, "${index + 1}", point.title, address = point.address)) }
        }
        own?.let { add(MapMarker(GeoPoint(it.latitude, it.longitude), "Я", "Вы здесь", true)) }
    }
    val lines = blocks.mapNotNull { b -> when { b.transport != null -> MapLine(b.transport.service.geometry, b.transport.mode != "bus"); b.walk != null -> MapLine(b.walk.availableGeometry, false); else -> null } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("Весь маршрут") })
            personalBlocks.filter { it.kind != "pause" }.forEachIndexed { index, block -> FilterChip(selected = selected == block.id, onClick = { selected = block.id }, label = { Text("${index + 1}. ${block.title}") }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { RouteMap(markers, lines, Modifier.fillMaxSize(), focusPoint = locate) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (blocks.any { it.transport != null && it.transport.mode != "bus" && it.transport.service.geometry.isEmpty() }) Text("Железнодорожный путь не получен: показаны только станции.", style = MaterialTheme.typography.bodySmall)
            if (blocks.any { it.transport?.mode == "bus" }) Text("Автобус: показаны остановки из расписания. Дорожная трасса рейса недоступна.", style = MaterialTheme.typography.bodySmall)
            if (personalBlocks.isEmpty()) Text("В вашем плане пока нет этапов с картой.", style = MaterialTheme.typography.bodySmall)
            if (blocks.any { it.transport?.service?.geometrySource == "derived_osm" }) Text("Путь по сети OSM может отличаться от фактического. © OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall)
            if (blocks.any { it.walk?.routeExpired == true }) Text("Обновите пешеходный путь в конструкторе: прошло 30 дней.", style = MaterialTheme.typography.bodySmall)
            Text(locationStatus, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { if (!permission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) else if (own != null) locate = own!!.let { GeoPoint(it.latitude, it.longitude) } else locationStatus = "Ожидаем координаты GPS. Проверьте, включена ли геопозиция." }, modifier = Modifier.fillMaxWidth()) { Text(if (permission) "Моё местоположение" else "Разрешить геопозицию") }
        }
    }
}
