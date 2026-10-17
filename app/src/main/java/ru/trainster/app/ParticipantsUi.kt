@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.trainster.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun PersonalNote(note: String, title: String = "Личная заметка организатора") {
    if (note.isNotBlank()) InfoCard(title, note)
}

private fun participantError(error: Exception): String = when (error) {
    is ApiException -> if (error.status == 409) "Личный план уже изменён. Вернитесь к списку и обновите участников перед повторным редактированием." else error.message ?: "Сервер не сохранил личный план."
    is IOException -> "Нет связи с сервером. Для изменения участников нужен интернет; полученный личный план у друзей сохранён на устройстве."
    else -> error.message ?: "Не удалось загрузить участников."
}

/** Organizer-only online editing. Published block IDs keep everyone's common timeline intact. */
@Composable
fun ParticipantsDialog(trip: Trip, repository: Repository, onClose: () -> Unit) {
    var members by remember(trip.id) { mutableStateOf<List<TripMember>>(emptyList()) }
    var editing by remember(trip.id) { mutableStateOf<TripMember?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val published = trip.executionBlocks

    LaunchedEffect(trip.id, reload) {
        loading = true; error = null
        try { members = repository.participants(trip.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = participantError(e) }
        finally { loading = false }
    }
    val back: () -> Unit = {
        if (!saving) {
            if (editing != null) { editing = null; error = null; reload++ } else onClose()
        }
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                topBar = { TopAppBar(title = { Text(editing?.let { "План: ${it.user.username}" } ?: "Участники") }, navigationIcon = { IconButton(onClick = back, enabled = !saving) { Icon(Icons.Outlined.ArrowBack, "Назад") } }) }
            ) { insets ->
                val selected = editing
                if (selected != null) {
                    key(selected.user.id, selected.personalization.revision) {
                        ParticipantPlanEditor(selected, published, Modifier.padding(insets), saving, error) { changes ->
                            scope.launch {
                                saving = true; error = null
                                try {
                                    val updated = repository.savePersonalization(trip.id, selected.user.id, changes)
                                    members = members.map { if (it.user.id == updated.user.id) updated else it }
                                    editing = null
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = participantError(e) }
                                finally { saving = false }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { InfoCard("Личный план для каждого", "Выберите, в каких опубликованных этапах участвует друг, и добавьте заметки только для него. Изменения применяются сразу; участник получит их при синхронизации и сможет читать без интернета.") }
                        if (published.isEmpty()) item { InfoCard("Сначала опубликуйте план", "После публикации здесь можно назначать этапы участникам.") }
                        item { OutlinedButton(onClick = { reload++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Обновить участников") } }
                        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        error?.let { detail -> item { Text(detail, color = MaterialTheme.colorScheme.error) } }
                        if (!loading && error == null && members.isEmpty()) item { InfoCard("Пока никого нет", "Передайте друзьям код ${trip.inviteCode}. После присоединения их аккаунты появятся здесь.") }
                        items(members, key = { it.user.id }) { member ->
                            Card(onClick = { editing = member; error = null }, enabled = published.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(member.user.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Участвует: ${published.count { it.id !in member.personalization.excludedBlockIds }} из ${published.size} этапов")
                                    if (member.personalization.note.isNotBlank()) Text(member.personalization.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    Text("Настроить участие и заметки", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ParticipantPlanEditor(
    member: TripMember, published: List<Block>, modifier: Modifier = Modifier,
    saving: Boolean = false, error: String? = null, onSave: (MemberPersonalization) -> Unit
) {
    val publishedIds = remember(published) { published.map { it.id }.toSet() }
    var excluded by remember(member.user.id, member.personalization.revision) { mutableStateOf(member.personalization.excludedBlockIds.intersect(publishedIds)) }
    var note by remember(member.user.id, member.personalization.revision) { mutableStateOf(member.personalization.note) }
    var notes by remember(member.user.id, member.personalization.revision) { mutableStateOf(member.personalization.blockNotes.filterKeys { it in publishedIds }) }
    val changed = excluded != member.personalization.excludedBlockIds.intersect(publishedIds) || note != member.personalization.note || notes != member.personalization.blockNotes.filterKeys { it in publishedIds }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Общий маршрут сохраняется. Галочка означает участие в этапе. Личные заметки видят только вы и ${member.user.username}.", style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(note, { if (it.length <= 10_000) note = it }, label = { Text("Общая заметка для участника") }, minLines = 2, enabled = !saving, modifier = Modifier.fillMaxWidth()) }
            items(published, key = { it.id }) { block ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = block.id !in excluded, onCheckedChange = { enabled -> excluded = if (enabled) excluded - block.id else excluded + block.id }, enabled = !saving)
                            Column(Modifier.weight(1f)) {
                                Text(block.title, fontWeight = FontWeight.SemiBold)
                                Text("${block.kindName} · ${displayTime(block.startsAt)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(if (block.id in excluded) "Без участия в этом этапе" else "Участвует в этапе", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(notes[block.id].orEmpty(), { text -> if (text.length <= 10_000) notes = if (text.isBlank()) notes - block.id else notes + (block.id to text) }, label = { Text("Личная заметка к этапу") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("${published.size - excluded.size} из ${published.size} этапов · изменения вступят в силу сразу", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onSave(MemberPersonalization(member.personalization.revision, excluded, note, notes)) }, enabled = changed && !saving && published.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Сохраняем…" else "Сохранить личный план")
                }
            }
        }
    }
}
