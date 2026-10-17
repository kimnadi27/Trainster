package ru.trainster.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.graphics.Bitmap
import java.io.File
import org.maplibre.android.offline.OfflineManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live integration check. No account, trip or fake map source is created.
 * Run download phase online; force-stop; run offline phase with SDK networking disabled.
 * The small Rostov pack is retained so the next process can verify persistent storage.
 */
class MapOfflineSmoke : Instrumentation() {
    private var phase = "download"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "download"; start() }
    override fun onStart() {
        if (phase == "journey_time") { JourneyTimeCheck(this).run(); return }
        if (phase == "personalization") { PersonalizationCheck(this).run(); return }
        if (phase.startsWith("feature_")) { TripFeaturesCheck(this).run(phase); return }
        if (phase.startsWith("place_")) { PlacePickerCheck(this).run(phase); return }
        val result = Bundle()
        var activity: MainActivity? = null
        var view: MapView? = null
        try {
            check(phase in listOf("download", "offline", "ui"))
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            if (phase == "ui") {
                runOnMainSync {
                    activity!!.setContent { TrainsterTheme {
                        RouteMap(listOf(MapMarker(GeoPoint(57.1911, 39.4024), "A", "Ростов: вокзал")), emptyList(), Modifier.fillMaxSize(), initialPoint = GeoPoint(57.187, 39.41))
                    } }
                }
                Thread.sleep(45_000)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "MapLibre Compose UI preview complete\n") })
                return
            }
            val prepared = CountDownLatch(1)
            val rendered = CountDownLatch(1)
            var map: MapLibreMap? = null
            runOnMainSync {
                if (phase == "offline") MapLibre.setConnected(false)
                view = MapView(activity!!).apply {
                    onCreate(null)
                    addOnDidFinishRenderingMapListener { fully -> if (fully && prepared.count == 0L) rendered.countDown() }
                    getMapAsync { ready ->
                        map = ready
                        ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(57.187, 39.41), 14.0))
                        ready.setStyle(BuildConfig.MAP_STYLE_URL) { prepared.countDown() }
                    }
                }
                activity!!.setContentView(view)
                view!!.onStart(); view!!.onResume()
            }
            check(prepared.await(50, TimeUnit.SECONDS)) { "Style failed in $phase phase" }
            check(rendered.await(50, TimeUnit.SECONDS)) { "Map failed to render in $phase phase" }
            var features = 0
            runOnMainSync {
                features = map!!.queryRenderedFeatures(RectF(0f, 0f, view!!.width.toFloat(), view!!.height.toFloat())).size
            }
            check(features > 0) { "No map features in $phase phase" }
            result.putInt("rendered_features", features)
            if (phase == "download") {
                var previous = emptySet<Long>()
                runOnMainSync {
                    check(!OfflineMaps.loading) { "Offline region list still loading" }
                    previous = OfflineMaps.areas.map { it.id }.toSet()
                    // Small viewport: approximately 5 x 3 km, never a regional bulk download.
                    OfflineMaps.download(map!!.projection.visibleRegion.latLngBounds, view!!.pixelRatio)
                }
                var done = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
                while (System.nanoTime() < deadline && !done) {
                    runOnMainSync {
                        val pack = OfflineMaps.areas.firstOrNull { it.id !in previous }
                        check(pack?.limited != true) { "Offline pack hit provider limit" }
                        check(pack?.error == null) { pack?.error.orEmpty() }
                        if (pack?.ready == true) {
                            done = true
                            result.putLong("pack_id", pack.id); result.putLong("pack_bytes", pack.bytes)
                        }
                    }
                    if (!done) Thread.sleep(500)
                }
                check(done) { "Pack did not finish" }
            } else {
                runOnMainSync {
                    check(OfflineMaps.areas.any { it.ready }) { "No completed persistent pack after restart" }
                }
                check(MapLibre.isConnected() == false) { "Offline network override was lost" }
            }
            val captured = CountDownLatch(1)
            runOnMainSync {
                map!!.snapshot { bitmap ->
                    File(targetContext.getExternalFilesDir(null), "maplibre-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    captured.countDown()
                }
            }
            check(captured.await(20, TimeUnit.SECONDS)) { "Map snapshot timed out" }
            if (phase == "download") {
                val cleared = CountDownLatch(1)
                var cacheError: String? = null
                runOnMainSync {
                    OfflineManager.getInstance(targetContext).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() { cleared.countDown() }
                        override fun onError(error: String) { cacheError = error; cleared.countDown() }
                    })
                }
                check(cleared.await(20, TimeUnit.SECONDS) && cacheError == null) { "Ambient cache cleanup failed" }
            }
            result.putString("stream", "MapLibre $phase PASS; features=$features; pack_bytes=${result.getLong("pack_bytes")}\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { view?.onPause(); view?.onStop(); view?.onDestroy(); activity?.finish() }
        }
    }
}
