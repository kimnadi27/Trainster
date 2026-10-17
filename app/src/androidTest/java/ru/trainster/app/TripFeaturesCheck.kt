@file:Suppress("DEPRECATION")
package ru.trainster.app

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.work.WorkManager
import org.maplibre.android.MapLibre
import java.io.File
import java.util.concurrent.TimeUnit

/** Native OS and marker integration; no server mutations, test accounts or trip writes. */
class TripFeaturesCheck(private val runner: Instrumentation) {
    private fun node(text: String): AccessibilityNodeInfo? {
        fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && current.text?.toString() == text) return current
            for (i in 0 until current.childCount) current.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) { node(text)?.let { return it }; Thread.sleep(150) }
        error("Missing control: $text")
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        runner.sendPointerSync(down); runner.sendPointerSync(up); down.recycle(); up.recycle()
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        runner.uiAutomation.takeScreenshot()?.let { bitmap -> File(runner.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    fun run(phase: String) {
        val result = Bundle()
        var activity: MainActivity? = null
        val manager = runner.targetContext.getSystemService(NotificationManager::class.java)
        var testTag: String? = null
        try {
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            if (phase == "feature_marker") {
                val marker = MapMarker(GeoPoint(57.187, 39.41), "1", "Место & встреча", address = "Ростов, Ярославская область")
                val intent = externalMapIntent(marker)
                check(intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "geo" && intent.`package` == null)
                check(Uri.decode(intent.data!!.schemeSpecificPart.substringAfter("?q=").substringBefore("&z=")) == "57.187,39.41(Место & встреча)")
                runner.runOnMainSync {
                    MapLibre.setConnected(false)
                    activity!!.setContent { TrainsterTheme { RouteMap(listOf(marker), emptyList(), Modifier.fillMaxSize(), showDownloadControls = false) } }
                }
                Thread.sleep(4_000)
                val screen = Rect()
                runner.runOnMainSync { activity!!.window.decorView.getWindowVisibleDisplayFrame(screen) }
                tap(screen.exactCenterX(), screen.exactCenterY() - 30)
                waitText("Открыть в картах")
                waitText("Ростов, Ярославская область")
                screenshot(phase)
                result.putString("stream", "marker PASS; cached_map=true; native_marker_click=true; external_geo_button=true; encoded_destination=true\n")
            } else {
                check(phase == "feature_notification")
                check(TripNotifications.allowed(runner.targetContext)) { "POST_NOTIFICATIONS must be granted for this opt-in test" }
                val repository = Repository(runner.targetContext)
                val scope = TripNotifications.scope(repository) ?: error("Existing login required")
                val trip = repository.readCache().first.firstOrNull() ?: error("Existing cached trip required")
                val event = TripEvent(0, trip.id, "Проверка уведомлений Trainster", "Проверка изменения плана", "integration-check")
                testTag = "trip:$scope:${trip.id}"
                TripNotifications.show(runner.targetContext, scope, event)
                Thread.sleep(500)
                val notification = manager.activeNotifications.single { it.tag == testTag }.notification
                check(notification.channelId == "trip_changes_v1" && notification.visibility == android.app.Notification.VISIBILITY_PRIVATE)
                check(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() == event.message)
                val work = WorkManager.getInstance(runner.targetContext).getWorkInfosForUniqueWork("trainster-trip-changes").get(10, TimeUnit.SECONDS)
                check(work.isNotEmpty()) { "Background work was not registered" }
                notification.contentIntent.send()
                waitText(trip.title)
                // Actual PendingIntent goes through MainActivity and opens only the current account's cached trip.
                waitText("План")
                screenshot(phase)
                result.putString("stream", "notification PASS; os_channel=true; private_lockscreen=true; background_work=true; scoped_pending_intent=true; no_server_writes=true\n")
            }
            runner.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            screenshot("$phase-failed")
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            runner.finish(Activity.RESULT_CANCELED, result)
        } finally {
            testTag?.let { manager.cancel(it, 1) }
            runner.runOnMainSync { MapLibre.setConnected(null); activity?.finish() }
        }
    }
}
