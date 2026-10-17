package ru.trainster.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/** A snapshot read by all time labels, refreshed when Android changes its time zone. */
object DeviceTime {
    var zone by mutableStateOf(ZoneId.systemDefault()); private set
    fun refresh() {
        // Clear java.util's cached default after the platform updates its setting.
        TimeZone.setDefault(null)
        zone = ZoneId.systemDefault()
    }
}

private val russian = Locale.forLanguageTag("ru-RU")
private val briefDateTime = DateTimeFormatter.ofPattern("dd.MM · HH:mm", russian)
private val clock = DateTimeFormatter.ofPattern("HH:mm", russian)
private val fullDate = DateTimeFormatter.ofPattern("d MMMM yyyy · EEE", russian)
private val entryDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", russian)

fun formatTimeEntry(value: String?, zone: ZoneId = DeviceTime.zone): String = localDateTime(value, zone)?.format(entryDateTime).orEmpty()
fun parseTimeEntry(value: String, zone: ZoneId = DeviceTime.zone, reference: String? = null): String? {
    if (value.isBlank()) return null
    // Preserve the exact instant of an unchanged field, including a repeated DST hour.
    if (reference != null && value.trim() == formatTimeEntry(reference, zone)) return reference
    return LocalDateTime.parse(value.trim(), entryDateTime).atZone(zone).toOffsetDateTime().toString()
}

fun localDateTime(value: String?, zone: ZoneId = DeviceTime.zone): ZonedDateTime? = instant(value)?.atZone(zone)
fun displayTime(value: String?): String = localDateTime(value)?.format(briefDateTime) ?: "Время не задано"
fun clockTime(value: String?): String = localDateTime(value)?.format(clock) ?: "—"
fun localDateLabel(value: String?): String = localDateTime(value)?.format(fullDate) ?: "Дата не задана"
fun localDayDifference(departure: String?, arrival: String?, zone: ZoneId = DeviceTime.zone): Long? {
    val start = localDateTime(departure, zone)?.toLocalDate() ?: return null
    val end = localDateTime(arrival, zone)?.toLocalDate() ?: return null
    return ChronoUnit.DAYS.between(start, end)
}
fun timeZoneNote(value: String? = null, endValue: String? = null): String {
    fun offset(time: Instant) = DeviceTime.zone.rules.getOffset(time).id.let { "UTC" + if (it == "Z") "+00:00" else it }
    val start = offset(instant(value) ?: Instant.now())
    val end = instant(endValue)?.let(::offset)
    return "Время телефона · $start" + if (end != null && end != start) " → $end · ${DeviceTime.zone.id}" else ""
}
fun durationText(seconds: Int): String {
    val duration = seconds.coerceAtLeast(0)
    val hours = duration / 3600
    val minutes = (duration % 3600) / 60
    return when {
        duration in 1..59 -> "меньше минуты"
        hours == 0 -> "$minutes мин"
        minutes == 0 -> "$hours ч"
        else -> "$hours ч $minutes мин"
    }
}
fun elapsedTravelSeconds(service: Service): Int {
    val start = instant(service.departure)
    val end = instant(service.arrival)
    if (start != null && end != null) {
        val elapsed = Duration.between(start, end).seconds
        if (elapsed in 0..Int.MAX_VALUE.toLong()) return elapsed.toInt()
    }
    return service.durationSeconds.coerceAtLeast(0)
}

@Composable
fun DeviceTimeBridge() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle) {
        DeviceTime.refresh()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) DeviceTime.refresh() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) DeviceTime.refresh() }
        lifecycle.addObserver(observer)
        onDispose { context.unregisterReceiver(receiver); lifecycle.removeObserver(observer) }
    }
}

@Composable
fun DeviceTimeSettings() {
    val context = LocalContext.current
    Text("${timeZoneNote()}\n${DeviceTime.zone.id}\nПри смене часового пояса время поездок обновляется автоматически.")
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) }) { Text("Настройки времени телефона") }
}
