@file:Suppress("DEPRECATION")
package ru.trainster.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import org.maplibre.android.MapLibre
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Live UI checks with real Photon data or a real downloaded offline map.
 * Existing session is read only to authorize the search; no trips are changed.
 */
class PlacePickerCheck(private val runner: Instrumentation) {
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && predicate(current)) return current
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                visit(child)?.let { return it }
            }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 35_000
        while (SystemClock.uptimeMillis() < deadline) {
            node(predicate)?.let { return it }
            Thread.sleep(150)
        }
        error("Expected picker control was not found")
    }
    private fun click(text: String) {
        var target = awaitNode { it.text?.toString() == text && it.isEnabled }
        while (!target.isClickable && target.parent != null) target = target.parent
        // A MapView resize can invalidate Compose accessibility action IDs. Use
        // the observed bounds, then verify the resulting selection callback.
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "Control has no visible bounds: $text" }
        tap(bounds.exactCenterX(), bounds.exactCenterY())
        Thread.sleep(250)
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 90, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        runner.sendPointerSync(down); runner.sendPointerSync(up)
        down.recycle(); up.recycle()
    }
    fun run(phase: String) {
        val bundle = Bundle()
        var activity: MainActivity? = null
        try {
            check(phase in listOf("place_address", "place_map_offline"))
            val offline = phase == "place_map_offline"
            val repository = Repository(runner.targetContext)
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val chosen = AtomicReference<WalkPoint?>()
            val selected = CountDownLatch(1)
            runner.runOnMainSync {
                if (offline) MapLibre.setConnected(false)
                activity!!.setContent { TrainsterTheme {
                    WalkPlacePicker(if (offline) PlacePickerMode.MAP else PlacePickerMode.ADDRESS,
                        GeoPoint(57.187, 39.41), emptyList(),
                        searchPlaces = { query, near -> check(!offline) { "Offline map picker tried address search" }; repository.searchPlaces(query, near) },
                        onDismiss = {}, onPick = { chosen.set(it); selected.countDown() })
                } }
            }
            if (!offline) {
                val input = awaitNode { it.isEditable }
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Ростов Великий Соборная площадь")
                }))
                click("Найти")
                click("Соборная площадь")
            } else {
                awaitNode { it.text?.toString()?.startsWith("Нажмите на нужное место") == true }
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (node { it.text?.toString() == "Загружаем карту…" } != null && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
                Thread.sleep(3000)
                val display = runner.targetContext.resources.displayMetrics
                tap(display.widthPixels / 2f, display.heightPixels / 2f)
                awaitNode { it.text?.toString() == "Место 1" }
            }
            click("Выбрать это место")
            check(selected.await(10, TimeUnit.SECONDS)) { "Picker did not return a point" }
            val point = chosen.get()!!
            check(point.latitude in 57.10..57.25 && point.longitude in 39.3..39.6)
            check(offline || !point.address.isNullOrBlank())
            check(offline || point.title == "Соборная площадь")
            check(WalkPoint.read(point.json()) == point) { "Place address lost during offline JSON roundtrip" }
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(runner.targetContext.getExternalFilesDir(null), "$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bundle.putString("stream", "$phase PASS; address_preserved=${point.address != null}; coordinate_valid=true; no_trip_writes=true\n")
            runner.finish(Activity.RESULT_OK, bundle)
        } catch (error: Throwable) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(runner.targetContext.getExternalFilesDir(null), "$phase-failed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bundle.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            runner.finish(Activity.RESULT_CANCELED, bundle)
        } finally {
            runner.runOnMainSync { MapLibre.setConnected(null); activity?.finish() }
        }
    }
}
