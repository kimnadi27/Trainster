@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.trainster.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private fun entryTime(value: String?, zone: ZoneId = DeviceTime.zone) = formatTimeEntry(value, zone)
private fun parseEntry(value: String, zone: ZoneId = DeviceTime.zone, reference: String? = null) = parseTimeEntry(value, zone, reference)

@Composable
fun BlockEditor(vm: TrainsterViewModel, kind: String, original: Block?, onDone: () -> Unit) {
    val previous = vm.selected?.blocks?.let { blocks -> if (original == null) blocks.lastOrNull() else blocks.getOrNull(blocks.indexOfFirst { it.id == original.id } - 1) }
    val scope = rememberCoroutineScope()
    var title by remember(original?.id, kind) { mutableStateOf(original?.title ?: when(kind) { "walk" -> "Прогулка"; "pause" -> "Пауза / ночёвка"; else -> "" }) }
    var notes by remember(original?.id, kind) { mutableStateOf(original?.notes.orEmpty()) }
    var starts by remember(original?.id, kind) { mutableStateOf(entryTime(original?.startsAt ?: previous?.endsAt)) }
    var ends by remember(original?.id, kind) { mutableStateOf(entryTime(original?.endsAt)) }
    var startsReference by remember(original?.id, kind) { mutableStateOf(original?.startsAt ?: previous?.endsAt) }
    var endsReference by remember(original?.id, kind) { mutableStateOf(original?.endsAt) }
    var entryZone by remember(original?.id, kind) { mutableStateOf(DeviceTime.zone) }
    val currentZone = DeviceTime.zone
    LaunchedEffect(currentZone) {
        if (entryZone != currentZone) {
            runCatching { parseEntry(starts, entryZone, startsReference) }.onSuccess { startsReference = it; starts = entryTime(it, currentZone) }
            runCatching { parseEntry(ends, entryZone, endsReference) }.onSuccess { endsReference = it; ends = entryTime(it, currentZone) }
            entryZone = currentZone
        }
    }
    var transport by remember(original?.id, kind) { mutableStateOf(original?.transport) }
    var walk by remember(original?.id, kind) {
        mutableStateOf(original?.walk ?: Walk(previous?.transport?.toStation?.let { station -> station.point?.let { listOf(WalkPoint(title = station.title, latitude = it.latitude, longitude = it.longitude)) } } ?: emptyList()))
    }
    var routing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pointDialog by remember { mutableStateOf<WalkPoint?>(null) }
    var placePicker by remember { mutableStateOf<PlacePickerMode?>(null) }
    var addCoordinates by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf(false) }
    val enabled = !routing && !vm.busy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(when(kind) { "transport" -> "Передвижение"; "walk" -> "Прогулка по городу"; else -> "Пауза или ночёвка" }, style = MaterialTheme.typography.headlineSmall)
        if (kind == "transport") {
            TransportPicker(vm.repository, original?.transport, previous?.transport?.toStation, previous?.transport?.mode, previous?.endsAt, enabled) { result ->
                transport = result
                if (result != null) { title = "${result.fromStation.title} → ${result.toStation.title}"; starts = entryTime(result.service.departure); ends = entryTime(result.service.arrival) }
            }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Название этапа") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        if (kind != "transport") {
            OutlinedTextField(starts, { starts = it }, label = { Text("Начало · ГГГГ-ММ-ДД ЧЧ:ММ") }, placeholder = { Text("2026-10-17 11:30") }, supportingText = { Text(timeZoneNote()) }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
            OutlinedTextField(ends, { ends = it }, label = { Text("Окончание · ГГГГ-ММ-ДД ЧЧ:ММ") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        }
        if (kind == "walk") {
            Text("Добавьте места прогулки по адресу или выберите на карте.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { placePicker = PlacePickerMode.ADDRESS }, enabled = enabled, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(6.dp)); Text("По адресу") }
                OutlinedButton(onClick = { placePicker = PlacePickerMode.MAP }, enabled = enabled, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(6.dp)); Text("На карте") }
            }
            RouteMap(
                markers = walk.points.mapIndexed { index, point -> MapMarker(point.point, "${index + 1}", point.title) },
                lines = listOf(MapLine(walk.availableGeometry, false)),
                initialPoint = walk.points.firstOrNull()?.point,
                modifier = Modifier.fillMaxWidth().height(320.dp),
                onLongPress = { point -> if (enabled) { editingPoint = false; pointDialog = WalkPoint(title = "Место ${walk.points.size + 1}", latitude = point.latitude, longitude = point.longitude) } }
            )
            TextButton(onClick = { addCoordinates = true }, enabled = enabled) { Text("Добавить по координатам") }
            walk.points.forEachIndexed { index, point ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${index + 1}. ${point.title}", style = MaterialTheme.typography.titleSmall)
                        point.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("${point.stayMinutes} мин на посещение", style = MaterialTheme.typography.bodySmall)
                        Row {
                            IconButton(onClick = { val p = walk.points.toMutableList(); p.add(index - 1, p.removeAt(index)); walk = Walk(p) }, enabled = index > 0 && enabled) { Icon(Icons.Outlined.ArrowUpward, "Выше") }
                            IconButton(onClick = { val p = walk.points.toMutableList(); p.add(index + 1, p.removeAt(index)); walk = Walk(p) }, enabled = index < walk.points.lastIndex && enabled) { Icon(Icons.Outlined.ArrowDownward, "Ниже") }
                            TextButton(onClick = { editingPoint = true; pointDialog = point }, enabled = enabled) { Text("Изменить") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { walk = Walk(walk.points.filterNot { it.id == point.id }) }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "Удалить место") }
                        }
                    }
                }
            }
            Button(onClick = {
                routing = true; error = null
                scope.launch {
                    try {
                        walk = vm.repository.routeWalk(walk.points)
                        val start = parseEntry(starts, reference = startsReference)?.let(::instant)
                        if (start != null && walk.durationSeconds != null) {
                            endsReference = start.plusSeconds(walk.durationSeconds!!.toLong() + walk.points.sumOf { it.stayMinutes }.toLong() * 60).toString()
                            ends = entryTime(endsReference)
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Не удалось построить пешеходный путь" } finally { routing = false }
                }
            }, enabled = enabled && walk.points.size >= 2 && walk.points.size <= 25, modifier = Modifier.fillMaxWidth()) { Text(if (routing) "Строим пешеходный путь…" else "Построить маршрут по точкам") }
            if (walk.availableGeometry.isNotEmpty()) Text("%.1f км · %s ходьбы + %d мин на посещения".format((walk.distanceMeters ?: 0.0) / 1000, durationText(walk.durationSeconds ?: 0), walk.points.sumOf { it.stayMinutes }), color = MaterialTheme.colorScheme.primary)
            else Text(if (walk.routeExpired) "Обновите пешеходный путь: старый путь Яндекс MapKit хранился 30 дней. Места сохранены." else "Пешеходный путь пока не построен. На карте сохранятся только места.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки для друзей") }, placeholder = { Text("Где встречаемся, какой выход, что взять с собой…") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = enabled)
        if (routing) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                val start = if (kind == "transport") transport?.service?.departure else parseEntry(starts, reference = startsReference)
                val end = if (kind == "transport") transport?.service?.arrival else parseEntry(ends, reference = endsReference)
                require(title.isNotBlank()) { "Укажите название этапа" }
                require(kind != "transport" || transport != null) { "Выберите рейс" }
                require(kind != "walk" || walk.points.isNotEmpty()) { "Добавьте хотя бы одно место прогулки" }
                require(start == null || end == null || instant(end)!! > instant(start)!!) { "Окончание должно быть позже начала" }
                vm.addOrReplace(Block(id = original?.id ?: newId(), kind = kind, title = title.trim(), startsAt = start, endsAt = end, notes = notes.trim(), transport = if (kind == "transport") transport else null, walk = if (kind == "walk") walk else null))
                onDone()
            } catch (e: Exception) { error = if (e is java.time.format.DateTimeParseException) "Введите дату и время в формате 2026-10-17 11:30 или оставьте поле пустым." else e.message }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Сохранить этап в план") }
        Text("Этап сохранится локально. В плане нажмите «Сохранить», затем «Опубликовать», чтобы обновить маршрут у друзей.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
    placePicker?.let { mode -> WalkPlacePicker(
        mode = mode, initialPoint = walk.points.lastOrNull()?.point, existingPoints = walk.points,
        searchPlaces = vm.repository::searchPlaces, onDismiss = { placePicker = null },
        onPick = { point -> placePicker = null; editingPoint = false; pointDialog = point }
    ) }
    pointDialog?.let { point -> PointDialog(point, onDismiss = { pointDialog = null }) { updated ->
        val points = if (editingPoint) walk.points.map { if (it.id == updated.id) updated else it } else walk.points + updated
        // Changing dwell time or the label does not invalidate the road geometry.
        walk = if (editingPoint && point.latitude == updated.latitude && point.longitude == updated.longitude) walk.copy(points = points) else Walk(points)
        pointDialog = null
    } }
    if (addCoordinates) PointDialog(WalkPoint(title = "Место ${walk.points.size + 1}", latitude = 0.0, longitude = 0.0), coordinates = true, onDismiss = { addCoordinates = false }) { point -> walk = Walk(walk.points + point); addCoordinates = false }
}

@Composable
private fun PointDialog(point: WalkPoint, coordinates: Boolean = false, onDismiss: () -> Unit, onSave: (WalkPoint) -> Unit) {
    var title by remember(point.id) { mutableStateOf(point.title) }
    var stay by remember(point.id) { mutableStateOf(point.stayMinutes.toString()) }
    var latitude by remember(point.id) { mutableStateOf(if (coordinates) "" else point.latitude.toString()) }
    var longitude by remember(point.id) { mutableStateOf(if (coordinates) "" else point.longitude.toString()) }
    val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Место прогулки") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            point.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(title, { title = it.take(300) }, label = { Text("Название") })
            OutlinedTextField(stay, { stay = it.filter(Char::isDigit).take(4) }, label = { Text("Минут на посещение") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (coordinates) {
                OutlinedTextField(latitude, { latitude = it }, label = { Text("Широта, например 57.185") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(longitude, { longitude = it }, label = { Text("Долгота, например 39.414") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(point.copy(title = title.trim(), stayMinutes = stay.toIntOrNull() ?: 0, latitude = lat!!, longitude = lon!!)) }, enabled = title.isNotBlank() && (stay.toIntOrNull() ?: 0) in 0..1440 && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) { Text("Добавить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun TransportPicker(repository: Repository, original: Transport?, previousStation: Station?, previousMode: String?, previousEnd: String?, enabled: Boolean, onSelected: (Transport?) -> Unit) {
    var mode by remember { mutableStateOf(original?.mode ?: previousMode ?: "suburban") }
    var from by remember { mutableStateOf(original?.fromStation ?: previousStation) }
    var to by remember { mutableStateOf(original?.toStation) }
    var date by remember { mutableStateOf(instant(original?.service?.departure ?: previousEnd)?.atZone(DeviceTime.zone)?.toLocalDate()?.toString() ?: LocalDate.now(DeviceTime.zone).toString()) }
    var services by remember { mutableStateOf<List<Service>?>(null) }
    var selected by remember { mutableStateOf(original?.service) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun invalidate() { services = null; selected = null; error = null; onSelected(null) }
    fun changeMode(value: String) {
        if (value != mode) { mode = value; from = null; to = null; invalidate() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(timeZoneNote(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "suburban", onClick = { changeMode("suburban") }, label = { Text("Электричка") }, enabled = enabled && !loading)
            FilterChip(selected = mode == "train", onClick = { changeMode("train") }, label = { Text("Поезд") }, enabled = enabled && !loading)
            FilterChip(selected = mode == "bus", onClick = { changeMode("bus") }, label = { Text("Автобус") }, enabled = enabled && !loading)
        }
        key(mode) {
            StationSearch(if (mode == "bus") "Откуда · остановка" else "Откуда", from, repository, mode, enabled && !loading) { from = it; invalidate() }
            StationSearch(if (mode == "bus") "Куда · остановка" else "Куда", to, repository, mode, enabled && !loading) { to = it; invalidate() }
        }
        if (mode == "bus") Text("Выберите автобусные остановки. Показаны рейсы с точным временем из Яндекс Расписаний; интервальные маршруты без времени не добавляются.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(date, { date = it; invalidate() }, label = { Text("Дата отправления · ГГГГ-ММ-ДД") }, singleLine = true, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            loading = true; error = null; services = null
            scope.launch {
                try { LocalDate.parse(date); services = repository.services(from!!, to!!, date, mode) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Не удалось найти рейсы" } finally { loading = false }
            }
        }, enabled = enabled && !loading && from != null && to != null && from?.code != to?.code, modifier = Modifier.fillMaxWidth()) { Text("Найти реальные рейсы") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (services?.isEmpty() == true) Text("Прямых рейсов на эту дату нет. Попробуйте другую дату или добавьте пересадку отдельными этапами.")
        services?.forEach { service ->
            OutlinedCard(onClick = {
                loading = true; error = null
                scope.launch {
                    try { val complete = repository.completeService(service, date); selected = complete; onSelected(Transport(mode, from!!, to!!, complete)); services = null }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Не удалось загрузить остановки" } finally { loading = false }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = enabled && !loading) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ServiceJourneySummary(service, mode, compact = true)
                }
            }
        }
        selected?.let { service ->
            ServiceJourneySummary(service, mode)
            Text("Остановок в участке: ${service.stops.size}", style = MaterialTheme.typography.bodySmall)
            if (mode != "bus") OutlinedButton(onClick = {
                loading = true; error = null
                scope.launch {
                    try { val traced = repository.railGeometry(service, date); selected = traced; onSelected(Transport(mode, from!!, to!!, traced)) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Не удалось получить железнодорожный путь" } finally { loading = false }
                }
            }, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth()) { Text("Построить путь по железной дороге") }
            Text(if (mode == "bus") "Остановки и расписание сохранятся в плане. Автомобильный путь рейса пока не строится." else if (service.geometry.isEmpty()) service.geometryNote.ifBlank { "Точная линия пути пока не получена. На карте будут показаны станции без выдуманной линии между ними." } else service.geometryNote.ifBlank { "Путь по сети OpenStreetMap может отличаться от фактического пути рейса. © OpenStreetMap contributors" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StationSearch(label: String, selected: Station?, repository: Repository, mode: String, enabled: Boolean, onSelected: (Station?) -> Unit) {
    var query by remember { mutableStateOf(selected?.title.orEmpty()) }
    var results by remember { mutableStateOf(emptyList<Station>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, selected?.code, mode) {
        results = emptyList(); error = null
        if (query.trim().length < 2 || query == selected?.title) { loading = false; return@LaunchedEffect }
        delay(400); loading = true
        try { results = repository.stations(query.trim(), mode) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка поиска станции" } finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(query, { query = it; onSelected(null) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = enabled, supportingText = { Text(if (selected != null) "Выбрана ${if (mode == "bus") "остановка" else "станция"} · ${selected.code}" else "Введите минимум 2 буквы и выберите ${if (mode == "bus") "остановку" else "станцию"}") })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        results.take(12).forEach { station -> TextButton(onClick = { query = station.title; results = emptyList(); onSelected(station) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(station.title, modifier = Modifier.fillMaxWidth()) } }
    }
}
