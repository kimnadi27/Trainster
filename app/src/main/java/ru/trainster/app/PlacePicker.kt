@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.trainster.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class PlacePickerMode { ADDRESS, MAP }

/** Selection stays local until the parent confirms the place name and dwell time. */
@Composable
fun WalkPlacePicker(
    mode: PlacePickerMode,
    initialPoint: GeoPoint?,
    existingPoints: List<WalkPoint>,
    searchPlaces: suspend (String, GeoPoint?) -> List<PlaceResult>,
    onDismiss: () -> Unit,
    onPick: (WalkPoint) -> Unit
) {
    var currentMode by remember { mutableStateOf(mode) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PlaceResult>()) }
    var selected by remember { mutableStateOf<WalkPoint?>(null) }
    var selectedResultId by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<GeoPoint?>(null) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableLongStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun findPlaces() {
        val text = query.trim()
        if (text.length < 3 || loading) return
        keyboard?.hide()
        val request = ++generation
        loading = true; error = null; searched = false; results = emptyList()
        job = scope.launch {
            try {
                val found = searchPlaces(text, initialPoint)
                if (request == generation) { results = found; searched = true }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == generation) error = when (e) {
                    is java.io.IOException -> "Нет связи с сервером. Попробуйте позже или выберите место на скачанной карте."
                    is ApiException -> e.message ?: "Не удалось найти адрес. Выберите точку на карте."
                    else -> "Не удалось найти адрес. Выберите точку на карте или попробуйте ещё раз."
                }
            }
            finally { if (request == generation) loading = false }
        }
    }
    fun pickMapPoint(point: GeoPoint) {
        keyboard?.hide()
        selected = WalkPoint(title = "Место ${existingPoints.size + 1}", latitude = point.latitude, longitude = point.longitude)
        selectedResultId = null; focus = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                TopAppBar(title = { Text("Добавить место") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Закрыть выбор места") }
                })
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = currentMode == PlacePickerMode.ADDRESS, onClick = { currentMode = PlacePickerMode.ADDRESS }, label = { Text("По адресу") })
                    FilterChip(selected = currentMode == PlacePickerMode.MAP, onClick = { currentMode = PlacePickerMode.MAP; keyboard?.hide() }, label = { Text("На карте") })
                }
                if (currentMode == PlacePickerMode.ADDRESS) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(query, { value ->
                            query = value.take(200); generation++; job?.cancel(); loading = false
                            results = emptyList(); selected = null; selectedResultId = null; searched = false; error = null
                        }, modifier = Modifier.weight(1f), label = { Text("Адрес или название") }, placeholder = { Text("Город, улица, дом") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { findPlaces() }))
                        FilledTonalButton(onClick = { findPlaces() }, enabled = query.trim().length >= 3 && !loading) { Text("Найти") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    when {
                        error != null -> Text(error!!, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        searched && results.isEmpty() -> Text("Ничего не найдено. Уточните город и адрес или выберите место на карте.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        results.isEmpty() && !loading -> Text("Укажите город и адрес или название места. Для поиска нужен интернет.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (results.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results, key = { it.id }) { place ->
                            Card(onClick = {
                                keyboard?.hide(); selected = place.walkPoint(); selectedResultId = place.id; focus = place.point
                            }, colors = CardDefaults.cardColors(containerColor = if (selectedResultId == place.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(place.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(place.address, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Text(if (currentMode == PlacePickerMode.MAP) "Нажмите на нужное место. Карту можно двигать и приближать." else "Выберите результат. При необходимости уточните точку нажатием на карте.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                val markers = existingPoints.mapIndexed { index, place -> MapMarker(place.point, "${index + 1}", place.title) } + listOfNotNull(selected?.let { MapMarker(it.point, "+", it.title) })
                RouteMap(markers, emptyList(), Modifier.weight(1f).fillMaxWidth(), initialPoint = initialPoint,
                    focusPoint = focus, onLongPress = ::pickMapPoint, onTap = ::pickMapPoint,
                    fitContent = false, showDownloadControls = false)
                Surface(shadowElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected != null) {
                            val point = selected!!
                            Text(point.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            point.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        } else Text("Выберите место на карте или найдите адрес.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { selected?.let(onPick) }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) { Text("Выбрать это место") }
                        Text("Поиск: Photon · © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
