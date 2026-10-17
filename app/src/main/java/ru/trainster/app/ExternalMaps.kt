package ru.trainster.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Coordinates, rather than the editable title, determine the destination. */
fun externalMapIntent(marker: MapMarker): Intent {
    val coordinates = "${marker.point.latitude},${marker.point.longitude}"
    val query = Uri.encode("$coordinates(${marker.title})")
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query&z=16"))
}

private fun openExternalMap(context: Context, marker: MapMarker): String? = try {
    // Starting directly also works with Android package visibility restrictions.
    context.startActivity(externalMapIntent(marker))
    null
} catch (_: ActivityNotFoundException) {
    "На устройстве нет приложения, которое открывает точки на карте. Установите Яндекс Карты, 2ГИС или другое приложение карт."
} catch (_: SecurityException) {
    "Не удалось открыть приложение карт. Проверьте его настройки."
}

@Composable
fun MapMarkerDialog(marker: MapMarker, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var error by remember(marker) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(marker.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            marker.address?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text("${marker.point.latitude}, ${marker.point.longitude}", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { error = openExternalMap(context, marker); if (error == null) onDismiss() }) { Text("Открыть в картах") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
