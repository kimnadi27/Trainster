@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.trainster.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun TrainsterApp(vm: TrainsterViewModel = viewModel()) {
    TripNotificationBridge(vm)
    DeviceTimeBridge()
    val snack = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editBlock by remember { mutableStateOf<Block?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.account?.id, lifecycle) {
        if (vm.account != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { vm.sync(); delay(30_000) }
        }
    }
    LaunchedEffect(vm.message) { vm.message?.let { val text = it; vm.message = null; snack.showSnackbar(text, duration = SnackbarDuration.Long) } }
    LaunchedEffect(vm.account?.id, vm.selectedId) { editor = null; editBlock = null }
    val trip = vm.selected
    val back: () -> Unit = { if (editor != null) { editor = null; editBlock = null } else vm.selectedId = null }
    BackHandler(enabled = editor != null || trip != null) { back() }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Column { Text(if (editor != null) if (editBlock == null) "Новый этап" else "Редактирование" else trip?.title ?: "Trainster", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (editor == null && trip == null) Text("Путешествия вместе", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) } },
                navigationIcon = { if (trip != null || editor != null) IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "Назад") } },
                actions = {
                    if (vm.account != null && editor == null) IconButton(onClick = vm::refresh, enabled = !vm.busy) { Icon(Icons.Outlined.Sync, "Обновить") }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Настройки сервера") }
                }
            )
        }
    ) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (vm.busy || vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                vm.account == null -> AuthScreen(vm)
                trip == null -> TripsScreen(vm)
                editor != null -> BlockEditor(vm, editor!!, editBlock, onDone = { editor = null; editBlock = null })
                else -> TripScreen(vm, trip, onEdit = { block -> editBlock = block; editor = block.kind }, onAdd = { kind -> editBlock = null; editor = kind })
            }
        }
    }
    if (settings) SettingsDialog(vm, onClose = { settings = false })
}

@Composable
private fun AuthScreen(vm: TrainsterViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Outlined.Train, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(58.dp))
        Text("Один маршрут.\nВся компания.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Соберите поездку из поездов, автобусов, прогулок и остановок. План останется с вами, даже когда пропадёт связь.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.login(username, password, register); password = "" }, enabled = username.isNotBlank() && password.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (register) "Создать аккаунт" else "Войти") }
        TextButton(onClick = { register = !register }) { Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться") }
        InfoCard("Сервер", vm.repository.server + "\nДля телефона укажите адрес компьютера в локальной сети через настройки сверху.")
    }
}

@Composable
private fun SettingsDialog(vm: TrainsterViewModel, onClose: () -> Unit) {
    var server by remember { mutableStateOf(vm.repository.server) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    AlertDialog(onDismissRequest = onClose, title = { Text("Подключение") }, text = {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(server, { server = it }, label = { Text("Адрес FastAPI сервера") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text("Эмулятор: http://10.0.2.2:8000\nТелефон: http://IP-компьютера:8000\nСмена адреса завершит вход. Локальные копии сохраняются отдельно для каждого сервера и аккаунта.", style = MaterialTheme.typography.bodySmall)
            Text("Карты MapLibre / OpenFreeMap — без ключа. Нужные области можно скачать на экране карты. Пешеходные пути: Valhalla / OpenStreetMap через сервер.", style = MaterialTheme.typography.bodySmall)
            Text("Расписания: ключ YANDEX_RASP_API_KEY на сервере. Ключи не вводятся в аккаунт пользователя.", style = MaterialTheme.typography.bodySmall)
            NotificationSettings()
            DeviceTimeSettings()
            TextButton(onClick = { uriHandler.openUri("https://openfreemap.org/tos/") }) { Text("Условия OpenFreeMap") }
            TextButton(onClick = { uriHandler.openUri("https://rasp.yandex.ru/") }) { Text("Данные Яндекс Расписаний") }
            TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap contributors") }
        }
    }, confirmButton = { TextButton(onClick = { vm.setServer(server); onClose() }, enabled = !vm.busy) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}

@Composable
private fun TripsScreen(vm: TrainsterViewModel) {
    var create by remember { mutableStateOf(false) }
    var join by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Привет, ${vm.account?.username}", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = vm::logout, enabled = !vm.busy) { Text("Выйти") }
        } }
        item { SyncStatus(vm) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { create = true }, modifier = Modifier.weight(1f), enabled = !vm.busy) { Icon(Icons.Outlined.Add, null); Text("Создать") }
            OutlinedButton(onClick = { join = true }, modifier = Modifier.weight(1f), enabled = !vm.busy) { Text("По коду") }
        } }
        if (vm.trips.isEmpty()) item { InfoCard("Куда отправимся?", "Создайте путешествие и соберите первый этап. Друзья смогут присоединиться после публикации плана.") }
        items(vm.trips, key = { it.id }) { trip ->
            ElevatedCard(onClick = { vm.selectedId = trip.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Train, null, tint = MaterialTheme.colorScheme.primary)
                        Text(trip.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (trip.description.isNotBlank()) Text(trip.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (trip.ownerId == vm.account?.id) "Вы организатор" else "Участник"} · ${trip.personalBlocks.size} этапов · ${statusName(trip.status)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (trip.id in vm.dirty) "Есть локальные изменения" else "Сохранено на устройстве · версия ${trip.version}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Новое путешествие") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Описание") })
        }
    }, confirmButton = { TextButton(onClick = { vm.create(title.trim(), description.trim()); create = false; title = ""; description = "" }, enabled = title.isNotBlank() && !vm.busy) { Text("Создать") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("Отмена") } })
    if (join) AlertDialog(onDismissRequest = { join = false }, title = { Text("Присоединиться") }, text = { OutlinedTextField(code, { code = it }, label = { Text("Код от организатора") }, singleLine = true) }, confirmButton = { TextButton(onClick = { vm.join(code); join = false; code = "" }, enabled = code.isNotBlank() && !vm.busy) { Text("Добавить") } }, dismissButton = { TextButton(onClick = { join = false }) { Text("Отмена") } })
}

@Composable
fun SyncStatus(vm: TrainsterViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (vm.online) "● Есть связь · ${vm.lastSync?.let(::displayTime) ?: "обновляем"}" else "● Локальная копия · ${vm.lastSync?.let { "обновлена ${displayTime(it)}" } ?: "синхронизации ещё не было"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(timeZoneNote(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun statusName(status: String) = when(status) { "active" -> "В пути"; "finished" -> "Завершено"; else -> "Планируем" }

@Composable
fun InfoCard(title: String, text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TripScreen(vm: TrainsterViewModel, trip: Trip, onEdit: (Block) -> Unit, onAdd: (String) -> Unit) {
    var tab by rememberSaveable(trip.id) { mutableIntStateOf(if (trip.status == "active") 1 else 0) }
    var stops by remember { mutableStateOf<Block?>(null) }
    var cloud by remember { mutableStateOf<Trip?>(null) }
    var metadata by remember { mutableStateOf(false) }
    var participants by remember(trip.id) { mutableStateOf(false) }
    val owner = trip.ownerId == vm.account?.id
    val plan = if (owner) trip.blocks else trip.personalBlocks
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) { listOf("План", "Сейчас", "Карта").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) }) } }
        when(tab) {
            0 -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { SyncStatus(vm) }
                if (trip.description.isNotBlank()) item { Text(trip.description) }
                if (owner) item {
                    InfoCard("Вы организатор · ${statusName(trip.status)}", "Код приглашения: ${trip.inviteCode}\nОпубликованная версия: ${trip.version}. Участники получают опубликованный план; ваши изменения сохраняются локально.")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(trip.inviteCode)); vm.message = "Код скопирован" }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Text("Код") }
                        TextButton(onClick = { metadata = true }, enabled = !vm.busy) { Text("Название") }
                        TextButton(onClick = { vm.cloudCopy { cloud = it } }, enabled = !vm.busy) { Text("Облако") }
                    }
                    OutlinedButton(onClick = { participants = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Group, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Участники и личные планы") }
                }
                if (!owner && (trip.personalization.revision > 0 || trip.personalization.note.isNotBlank())) item {
                    InfoCard("Ваш личный план", "${plan.size} из ${trip.blocks.size} общих этапов. Организатор настроил участие для вас." + if (trip.personalization.note.isBlank()) "" else "\n\n${trip.personalization.note}")
                }
                if (vm.scheduleConfigured == false && owner) item { InfoCard("Подключите расписания", "На сервере пока не задан YANDEX_RASP_API_KEY. После настройки станут доступны реальные станции и рейсы.") }
                if (trip.id in vm.dirty) item { InfoCard("Черновик на устройстве", "Изменения защищены от фоновой синхронизации. Сохраните их в облаке, затем опубликуйте для друзей.") }
                else if (owner && trip.hasUnpublishedChanges) item { InfoCard("Есть неопубликованные изменения", "Черновик сохранён в облаке. Друзья и экран «Сейчас» используют опубликованную версию ${trip.version}.") }
                if (owner) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = vm::save, enabled = !vm.busy && trip.id in vm.dirty, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                    Button(onClick = vm::publish, enabled = !vm.busy && trip.blocks.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Опубликовать") }
                } }
                planWarnings(plan).forEach { warning -> item { Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                if (plan.isEmpty()) item { if (owner) InfoCard("Первый этап", "Добавьте передвижение, прогулку или ночёвку. Начало следующего этапа подставится из предыдущего.") else InfoCard("Нет назначенных этапов", "Организатор пока не включил этапы в ваш личный план. Общий статус поездки доступен на экране «Сейчас».") }
                itemsIndexed(plan, key = { _, block -> block.id }) { index, block ->
                    if (index > 0) {
                        val previous = plan[index - 1]
                        val end = instant(previous.endsAt); val start = instant(block.startsAt)
                        if (end != null && start != null && start > end) Text("↓  ${durationText(Duration.between(end, start).seconds.toInt())} между этапами", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    BlockCard(block, index + 1, active = trip.currentBlockId == block.id, onClick = { stops = block })
                    if (!owner && trip.personalNoteFor(block.id).isNotBlank()) PersonalNote(trip.personalNoteFor(block.id))
                    if (owner) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.moveBlock(block.id, -1) }, enabled = index > 0 && !vm.busy) { Icon(Icons.Outlined.ArrowUpward, "Выше") }
                        IconButton(onClick = { vm.moveBlock(block.id, 1) }, enabled = index < trip.blocks.lastIndex && !vm.busy) { Icon(Icons.Outlined.ArrowDownward, "Ниже") }
                        TextButton(onClick = { onEdit(block) }, enabled = !vm.busy) { Text("Изменить") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.removeBlock(block.id) }, enabled = !vm.busy) { Icon(Icons.Outlined.DeleteOutline, "Удалить этап") }
                    }
                }
                if (!owner && trip.blocks.any { !trip.participatesIn(it.id) }) {
                    item { InfoCard("Этапы без вашего участия", "Эти этапы остаются у группы. Заметки к ним тоже сохранены на вашем устройстве.") }
                    items(trip.blocks.filterNot { trip.participatesIn(it.id) }, key = { "skipped-${it.id}" }) { skipped ->
                        TextButton(onClick = { stops = skipped }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(skipped.title, fontWeight = FontWeight.SemiBold)
                                Text(displayTime(skipped.startsAt), style = MaterialTheme.typography.bodySmall)
                                if (trip.personalNoteFor(skipped.id).isNotBlank()) Text(trip.personalNoteFor(skipped.id), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (owner) item {
                    Text("Добавить этап", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("transport" to "Транспорт", "walk" to "Прогулка", "pause" to "Пауза").forEach { (kind, label) -> OutlinedButton(onClick = { onAdd(kind) }, enabled = !vm.busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 10.dp)) { Text(label) } }
                    }
                }
                item { Text("Расписания: Яндекс Расписания. Время плановое; задержки не отслеживаются. План и линии сохраняются на устройстве. Для карты без интернета заранее скачайте нужные области.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            1 -> NowScreen(vm, trip, owner, onStops = { stops = it })
            else -> TripMapScreen(trip)
        }
    }
    if (participants && owner) ParticipantsDialog(trip, vm.repository, onClose = { participants = false })
    stops?.let { block -> ModalBottomSheet(onDismissRequest = { stops = null }) { BlockDetails(block, Modifier.fillMaxWidth().heightIn(max = 600.dp), personalNote = if (owner) "" else trip.personalNoteFor(block.id)); Spacer(Modifier.height(30.dp)) } }
    cloud?.let { remote -> AlertDialog(onDismissRequest = { cloud = null }, title = { Text("Облачный черновик") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${remote.title}\nРевизия: ${remote.draftRevision}\nОбновлён: ${displayTime(remote.updatedAt)}")
            remote.blocks.forEachIndexed { index, b -> Text("${index + 1}. ${b.title}\n${displayTime(b.startsAt)}", style = MaterialTheme.typography.bodySmall) }
            Text("Загрузить облачный — заменить локальные изменения. Выбрать мой — разрешить следующее сохранение поверх этой облачной ревизии.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { vm.replaceWithCloud(remote); cloud = null }) { Text("Загрузить облачный") } }, dismissButton = { TextButton(onClick = { vm.keepLocalAgainst(remote); cloud = null }) { Text("Выбрать мой") } }) }
    if (metadata) {
        var title by remember { mutableStateOf(trip.title) }; var description by remember { mutableStateOf(trip.description) }
        AlertDialog(onDismissRequest = { metadata = false }, title = { Text("О путешествии") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Название") }); OutlinedTextField(description, { description = it }, label = { Text("Описание") }) } }, confirmButton = { TextButton(onClick = { vm.editTrip(trip.copy(title = title.trim(), description = description.trim())); metadata = false }, enabled = title.isNotBlank()) { Text("Готово") } }, dismissButton = { TextButton(onClick = { metadata = false }) { Text("Отмена") } })
    }
}

@Composable
fun BlockCard(block: Block, index: Int, active: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${index.toString().padStart(2, '0')}  ·  ${block.kindName}${if (active) " · СЕЙЧАС" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(block.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${displayTime(block.startsAt)} → ${displayTime(block.endsAt)}", style = MaterialTheme.typography.bodySmall)
            block.transport?.service?.let { Text("№ ${it.number} · ${durationText(elapsedTravelSeconds(it))}", style = MaterialTheme.typography.bodyMedium) }
            block.walk?.let { walk -> Text("${walk.points.size} мест${walk.distanceMeters?.let { " · %.1f км".format(it / 1000) }.orEmpty()}${walk.durationSeconds?.let { " · ${durationText(it)} ходьбы" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium) }
            if (block.notes.isNotBlank()) Text(block.notes, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowScreen(vm: TrainsterViewModel, trip: Trip, owner: Boolean, onStops: (Block) -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(20_000) } }
    val execution = trip.executionBlocks
    val block = execution.firstOrNull { it.id == trip.currentBlockId }
    val index = execution.indexOfFirst { it.id == block?.id }
    val next = execution.drop(if (index >= 0) index + 1 else 0).firstOrNull { owner || trip.participatesIn(it.id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SyncStatus(vm)
        if (owner) Text("Опубликованный план · версия ${trip.version}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(statusName(trip.status), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!owner && trip.personalization.note.isNotBlank()) PersonalNote(trip.personalization.note, "Для вас от организатора")
        when {
            trip.status == "planning" -> InfoCard("План готовится", "После публикации организатор запускает поездку и выбирает текущий этап. Без связи сохраняется последнее полученное состояние.")
            trip.status == "finished" -> InfoCard("До следующей поездки", "Путешествие завершено. Весь маршрут остаётся в плане.")
            block != null && !owner && !trip.participatesIn(block.id) -> {
                InfoCard("Этот этап без вашего участия", "У группы сейчас: ${block.title}. Организатор исключил этот этап из вашего плана.")
                if (trip.personalNoteFor(block.id).isNotBlank()) PersonalNote(trip.personalNoteFor(block.id))
                if (next != null) InfoCard("Ваш следующий этап", "${next.title}\n${displayTime(next.startsAt)}")
                else InfoCard("Дальнейших этапов для вас нет", "Общий статус поездки продолжит обновляться при наличии связи.")
            }
            block != null -> {
                BlockCard(block, index + 1, active = true, onClick = { onStops(block) })
                if (!owner && trip.personalNoteFor(block.id).isNotBlank()) PersonalNote(trip.personalNoteFor(block.id))
                block.transport?.let { transport ->
                    InfoCard("Нам выходить: ${transport.toStation.title}", "Плановое прибытие ${displayTime(block.endsAt)}")
                    val nextStop = transport.service.stops.firstOrNull { (instant(it.arrival) ?: instant(it.departure))?.isAfter(now) == true }
                    InfoCard("Следующая остановка по расписанию", nextStop?.let { "${it.station.title} · ${clockTime(it.arrival ?: it.departure)}" } ?: "Будущих остановок в сохранённом расписании нет.")
                    Text("Положение транспорта и задержки не подтверждаются расписанием. Своё местоположение можно посмотреть на карте.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (next != null) InfoCard("Дальше", "${next.title}\n${displayTime(next.startsAt)}")
            }
            else -> InfoCard("Текущий этап недоступен", "Обновите план. У организатора текущий этап может отличаться от неопубликованного черновика.")
        }
        if (owner && execution.isNotEmpty()) {
            val ready = !vm.busy && trip.version > 0
            if (trip.status != "active") Button(onClick = { vm.state("active", execution.first().id) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(if (trip.status == "finished") "Запустить снова" else "Начать путешествие") }
            if (trip.status == "active") {
                Text("Переключить текущий этап", style = MaterialTheme.typography.titleMedium)
                execution.forEachIndexed { i, b -> OutlinedButton(onClick = { vm.state("active", b.id) }, enabled = ready && b.id != trip.currentBlockId, modifier = Modifier.fillMaxWidth()) { Text("${i + 1}. ${b.title}", maxLines = 2) } }
                Button(onClick = { vm.state("finished", null) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Завершить путешествие") }
                OutlinedButton(onClick = { vm.state("planning", null) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Вернуть в планирование") }
            }
            if (!ready && !vm.busy) Text("Для запуска нужен опубликованный план.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BlockDetails(block: Block, modifier: Modifier = Modifier, personalNote: String = "") {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    LazyColumn(modifier, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(block.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (block.transport == null) item { Text("${displayTime(block.startsAt)} → ${displayTime(block.endsAt)}") }
        block.transport?.let { transport ->
            item { ServiceJourneySummary(transport.service, transport.mode) }
            item { Text(timeZoneNote(transport.service.departure, transport.service.arrival), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (block.notes.isNotBlank()) item { Text(block.notes) }
        if (personalNote.isNotBlank()) item { PersonalNote(personalNote) }
        block.transport?.service?.let { service ->
            item { Text("Остановки по маршруту", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (service.stops.isEmpty()) item { Text("Остановки не были получены. Обновите рейс в конструкторе.") }
            else item { StopScheduleHeader() }
            items(service.stops) { stop -> StopScheduleRow(stop, service.departure) }
            item { Text("Расписание плановое, без учёта задержек. Сохранено ${displayTime(service.fetchedAt)}. Данные: Яндекс Расписания.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (service.sourceUrl.startsWith("https://")) item { TextButton(onClick = { uri.openUri(service.sourceUrl) }) { Text("Открыть источник") } }
        }
        block.walk?.let { walk -> itemsIndexed(walk.points) { i, point -> Text("${i + 1}. ${point.title}${if (point.stayMinutes > 0) " · ${point.stayMinutes} мин" else ""}") } }
    }
}
