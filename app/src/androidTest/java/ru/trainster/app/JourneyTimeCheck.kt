package ru.trainster.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Exercises the actual sheet and an Android time-zone change; no trip or account mutations. */
class JourneyTimeCheck(private val runner: Instrumentation) {
    private fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    private fun await(condition: () -> Boolean, failure: String) {
        val until = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(20)
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(150) }
        error(failure)
    }
    private fun hasText(text: String): Boolean {
        fun visit(current: AccessibilityNodeInfo): Boolean {
            if (current.isVisibleToUser && current.text?.toString()?.contains(text) == true) return true
            for (i in 0 until current.childCount) current.getChild(i)?.let { if (visit(it)) return true }
            return false
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit) == true
    }
    private fun screenshot(name: String) { runner.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(runner.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } }
    @OptIn(ExperimentalMaterial3Api::class)
    fun run() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val start = Station("test-from", "Москва (Ярославский вокзал)")
            val end = Station("test-to", "Ростов Великий")
            val service = Service("check", "6334", "Москва — Ростов", "2026-10-17T23:40:00+03:00", "2026-10-18T02:10:00+03:00", 12, start, end,
                stops = listOf(Stop(start, null, "2026-10-17T23:40:00+03:00", null), Stop(end, "2026-10-18T02:10:00+03:00", null, null)),
                fetchedAt = "2026-10-17T12:00:00+03:00")
            check(elapsedTravelSeconds(service) == 9000) { "Elapsed time must use absolute timestamps" }
            val crossing = service.copy(departure = "2026-10-17T23:50:00+03:00", arrival = "2026-10-18T03:20:00+05:00")
            check(elapsedTravelSeconds(crossing) == 5400) { "Station offset difference inflated elapsed time" }
            check(localDayDifference(crossing.departure, crossing.arrival, ZoneId.of("Europe/Moscow")) == 1L)
            check(localDayDifference(crossing.departure, crossing.arrival, ZoneId.of("Asia/Yekaterinburg")) == 0L)
            check(localDateTime(service.departure, ZoneId.of("Europe/Moscow"))?.hour == 23)
            check(localDateTime(service.departure, ZoneId.of("Asia/Yekaterinburg"))?.hour == 1)
            val dst = service.copy(departure = "2026-11-01T01:30:00-04:00", arrival = "2026-11-01T01:30:00-05:00")
            val repeated = "2026-11-01T01:30:00-05:00"
            val newYork = ZoneId.of("America/New_York")
            check(parseTimeEntry(formatTimeEntry(repeated, newYork), newYork, repeated) == repeated) { "Unchanged walk time lost its DST offset" }
            check(formatTimeEntry(parseTimeEntry("2026-11-01 01:30", newYork, repeated), ZoneId.of("Europe/Moscow")) == "2026-11-01 09:30")
            check(elapsedTravelSeconds(dst) == 3600) { "Daylight-saving clock change lost one hour" }
            check(Service.read(service.json()).departure == service.departure) { "Formatting rewrote the cached timestamp" }
            check(durationText(9000) == "2 ч 30 мин" && durationText(3600) == "1 ч")

            shell("cmd alarm set-timezone Europe/Moscow")
            activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val block = Block(kind = "transport", title = "Москва → Ростов", startsAt = service.departure, endsAt = service.arrival,
                transport = Transport("suburban", start, end, service))
            runner.runOnMainSync { activity!!.setContent { TrainsterTheme {
                DeviceTimeBridge()
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    BlockDetails(block, Modifier.fillMaxWidth().heightIn(max = 650.dp))
                    Spacer(Modifier.height(20.dp))
                }
            } } }
            await({ hasText("23:40") && hasText("02:10") && hasText("2 ч 30 мин") }, "Moscow journey summary not rendered")
            await({ hasText("17 октября 2026") && hasText("18 октября 2026") }, "Departure and next-day arrival dates missing")
            screenshot("journey-time-moscow")

            shell("cmd alarm set-timezone Asia/Yekaterinburg")
            await({ DeviceTime.zone.id == "Asia/Yekaterinburg" }, "Time-zone broadcast was not handled")
            await({ hasText("01:40") && hasText("04:10") && hasText("2 ч 30 мин") && hasText("UTC+05:00") }, "Open sheet did not refresh for new time zone")
            check(localDayDifference(service.departure, service.arrival) == 0L)
            screenshot("journey-time-ekaterinburg")
            shell("cmd alarm set-timezone Europe/Moscow")
            await({ DeviceTime.zone.id == "Europe/Moscow" && hasText("23:40") }, "Time zone was not restored")
            result.putString("stream", "journey time PASS; next_day_dates=true; different_offsets=true; elapsed_duration=true; daylight_saving=true; live_timezone_broadcast=true; no_trip_writes=true\n")
            runner.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            screenshot("journey-time-failed")
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            runner.finish(Activity.RESULT_CANCELED, result)
        } finally {
            shell("cmd alarm set-timezone Europe/Moscow")
            runner.runOnMainSync { activity?.finish() }
        }
    }
}
