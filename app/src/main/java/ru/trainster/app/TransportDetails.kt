package ru.trainster.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun transportLabel(mode: String): String = when (mode) {
    "train" -> "Поезд"
    "bus" -> "Автобус"
    else -> "Электричка"
}

private fun journeyDayLabel(days: Long): String {
    val ending = when {
        days % 100 in 11L..14L -> "дней"
        days % 10 == 1L -> "день"
        days % 10 in 2L..4L -> "дня"
        else -> "дней"
    }
    return "+$days $ending"
}

/** A single display zone comes from the phone; elapsed travel time uses absolute instants. */
@Composable
fun ServiceJourneySummary(service: Service, mode: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val dayDifference = localDayDifference(service.departure, service.arrival)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 16.dp else 22.dp), color = if (compact) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(if (compact) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (mode == "bus") Icons.Outlined.DirectionsBus else Icons.Outlined.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 24.dp else 30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(transportLabel(mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(service.number.takeIf { it.isNotBlank() }?.let { "№ $it" } ?: "Рейс", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("В пути", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(durationText(elapsedTravelSeconds(service)), style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dayLabel = dayDifference?.takeIf { it > 0 }?.let(::journeyDayLabel)
                if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.4f) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        JourneyEndpoint("Отправление", service.departure, service.fromStation.title, compact, Modifier.fillMaxWidth())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        JourneyEndpoint("Прибытие", service.arrival, service.toStation.title, compact, Modifier.fillMaxWidth(), dayLabel)
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    JourneyEndpoint("Отправление", service.departure, service.fromStation.title, compact, Modifier.weight(1f))
                    Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 28.dp).size(18.dp))
                    JourneyEndpoint("Прибытие", service.arrival, service.toStation.title, compact, Modifier.weight(1f), dayLabel)
                }
            }
            if (!compact && service.title.isNotBlank()) Text(service.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun JourneyEndpoint(label: String, time: String, station: String, compact: Boolean, modifier: Modifier, dayLabel: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(clockTime(time), style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(station, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(localDateLabel(time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        dayLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun StopScheduleHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Остановка", Modifier.weight(1.25f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Прибытие", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Отправление", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StopScheduleRow(stop: Stop, baseDeparture: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text(stop.station.title, Modifier.weight(1.25f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            StopScheduleTime(stop.arrival, baseDeparture, Modifier.weight(1f))
            StopScheduleTime(stop.departure, baseDeparture, Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StopScheduleTime(value: String?, baseDeparture: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(clockTime(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (value != null && localDayDifference(baseDeparture, value)?.let { it != 0L } == true) Text(localDateLabel(value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
