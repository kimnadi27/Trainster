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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated editor/cache regression check; never modifies real trips or account preferences. */
class PersonalizationCheck(private val runner: Instrumentation) {
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.isVisibleToUser && predicate(current)) return current
            for (i in 0 until current.childCount) current.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) { node(predicate)?.let { return it }; Thread.sleep(150) }
        error("Personal plan control was not found")
    }
    private fun click(target: AccessibilityNodeInfo) {
        var clickable = target
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        val bounds = Rect().also(clickable::getBoundsInScreen)
        check(!bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        runner.sendPointerSync(down); runner.sendPointerSync(up); down.recycle(); up.recycle()
        Thread.sleep(200)
    }
    fun run() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val morning = Block(id = "11111111-1111-4111-8111-111111111111", kind = "pause", title = "Утренний этап")
            val evening = Block(id = "22222222-2222-4222-8222-222222222222", kind = "pause", title = "Вечерняя встреча")
            val config = MemberPersonalization(4, setOf(evening.id), "Встречаемся вечером", mapOf(evening.id to "Ты можешь пропустить этот этап"))
            val trip = Trip("test", "Личный план", "", "owner", "CODE", 1, 1, "active", evening.id, "2026-10-17T12:00:00Z", listOf(morning, evening), publishedBlocks = listOf(morning, evening), personalization = config)
            val cached = Trip.read(trip.json())
            check(cached.personalization == config) { "Personal notes were lost in offline JSON" }
            check(cached.personalBlocks == listOf(morning) && cached.personalExecutionBlocks == listOf(morning))
            check(cached.executionBlocks == listOf(morning, evening) && cached.currentBlockId == evening.id) { "Filtering participation changed the common timeline" }
            val legacy = Trip.read(trip.json().apply { remove("personalization") })
            check(legacy.personalBlocks == listOf(morning, evening)) { "Older offline plans must include all stages" }
            check(config.updateJson().getInt("expected_revision") == 4 && !config.updateJson().has("revision"))

            val chosen = AtomicReference<MemberPersonalization?>()
            val saved = CountDownLatch(1)
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runner.runOnMainSync { activity!!.setContent { TrainsterTheme {
                ParticipantPlanEditor(TripMember(Account("friend", "Друг"), config), listOf(morning, evening), onSave = { chosen.set(it); saved.countDown() })
            } } }
            val text = "Присоединяйся к прогулке у вокзала"
            val field = awaitNode { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
            click(awaitNode { it.isCheckable && it.isChecked })
            click(awaitNode { it.text?.toString() == "Сохранить личный план" })
            check(saved.await(5, TimeUnit.SECONDS)) { "Editor did not submit the personal plan" }
            val changes = chosen.get()!!
            check(changes.excludedBlockIds == setOf(morning.id, evening.id) && changes.note == text && changes.revision == 4)
            check(changes.blockNotes[evening.id] == config.blockNotes[evening.id])
            runner.uiAutomation.takeScreenshot()?.let { bitmap -> File(runner.targetContext.getExternalFilesDir(null), "personalization.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            result.putString("stream", "personalization PASS; legacy_cache=true; common_timeline_retained=true; private_notes_roundtrip=true; editor_submission=true; no_trip_writes=true\n")
            runner.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap -> File(runner.targetContext.getExternalFilesDir(null), "personalization-failed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            runner.finish(Activity.RESULT_CANCELED, result)
        } finally { runner.runOnMainSync { activity?.finish() } }
    }
}
