package ru.trainster.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class TripEvent(val id: Long, val tripId: String, val title: String, val message: String, val actorId: String) {
    companion object { fun read(j: JSONObject) = TripEvent(j.getLong("id"), j.getString("trip_id"), j.getString("trip_title"), j.getString("message"), j.getString("actor_id")) }
}

data class TripNotificationDestination(val tripId: String, val scope: String)

object NotificationNavigation {
    var pending by mutableStateOf<TripNotificationDestination?>(null); private set
    fun accept(intent: Intent?) {
        val trip = intent?.getStringExtra("trip_id") ?: return
        val scope = intent.getStringExtra("account_scope") ?: return
        if (trip.length in 1..128 && scope.length == 64) pending = TripNotificationDestination(trip, scope)
        intent.removeExtra("trip_id"); intent.removeExtra("account_scope")
    }
    fun clear() { pending = null }
}

/** One cursor per account and server. Polling never writes over the organizer's offline draft. */
object TripNotifications {
    private const val CHANNEL = "trip_changes_v1"
    private const val WORK = "trainster-trip-changes"
    private val pollMutex = Mutex()
    var enabled by mutableStateOf(true); private set
    private fun prefs(context: Context) = context.getSharedPreferences("trip_notifications", Context.MODE_PRIVATE)
    fun initialize(context: Context) {
        enabled = prefs(context).getBoolean("enabled", true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Изменения путешествий", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Запуск, новые версии плана, смена этапа и личные изменения"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }
    fun scope(repository: Repository): String? = repository.session?.user?.id?.let { user ->
        MessageDigest.getInstance("SHA-256").digest("${repository.server}/$user".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value; prefs(context).edit().putBoolean("enabled", value).apply()
        schedule(context, Repository(context))
        if (!value) NotificationManagerCompat.from(context).cancelAll()
    }
    fun permissionRequested(context: Context) = prefs(context).getBoolean("permission_requested", false)
    fun markPermissionRequested(context: Context) { prefs(context).edit().putBoolean("permission_requested", true).apply() }
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun schedule(context: Context, repository: Repository) {
        val manager = WorkManager.getInstance(context)
        val scope = scope(repository)
        if (!enabled || scope == null) {
            manager.cancelUniqueWork(WORK)
            if (scope == null) NotificationManagerCompat.from(context).cancelAll()
            return
        }
        val request = PeriodicWorkRequestBuilder<TripChangesWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("account_scope" to scope))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    private fun isCurrent(context: Context, expected: String) = enabled && scope(Repository(context)) == expected

    /** Returns whether the foreground plan should be refreshed. First use sets a baseline, without old alerts. */
    suspend fun poll(context: Context, expected: String): Boolean = pollMutex.withLock {
        if (!isCurrent(context, expected)) return@withLock false
        val repository = Repository(context)
        val actor = repository.session?.user?.id ?: return@withLock false
        val preferences = prefs(context)
        val key = "cursor_$expected"
        var after: Long? = if (preferences.contains(key)) preferences.getLong(key, 0) else null
        var changed = false
        // Bounded catch-up. The next poll continues from the saved cursor if there is more.
        repeat(10) {
            val query = buildMap { put("limit", "100"); after?.let { put("after", it.toString()) } }
            val response = JSONObject(repository.request("/events", query = query))
            if (!isCurrent(context, expected)) return@withLock false
            val events = response.optJSONArray("events").objects(TripEvent::read)
            val cursor = response.getLong("cursor")
            val remoteEvents = events.filter { it.actorId != actor }
            if (after != null && remoteEvents.isNotEmpty()) {
                changed = true
                remoteEvents.groupBy { it.tripId }.values.forEach { list ->
                    if (allowed(context) && isCurrent(context, expected)) show(context, expected, list.last())
                }
            }
            // Persist only after processing; a retry replaces the same trip notification.
            withContext(Dispatchers.IO) { check(preferences.edit().putLong(key, cursor).commit()) { "Не удалось сохранить отметку уведомлений" } }
            if (!response.optBoolean("has_more")) return@withLock changed
            if (after != null && cursor <= after!!) return@withLock changed
            after = cursor
        }
        changed
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(context: Context, scope: String, event: TripEvent) {
        if (!allowed(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("trainster://trip/${Uri.encode(event.tripId)}?scope=$scope"))
            .putExtra("trip_id", event.tripId).putExtra("account_scope", scope)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title).setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setContentIntent(pending).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify("trip:$scope:${event.tripId}", 1, notification)
    }
}

class TripChangesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val expected = inputData.getString("account_scope") ?: return Result.success()
        if (TripNotifications.scope(Repository(applicationContext)) != expected || !TripNotifications.enabled) return Result.success()
        return try {
            TripNotifications.poll(applicationContext, expected)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.status in listOf(401, 403, 404)) Result.success() else Result.retry() }
        catch (_: Exception) { Result.retry() }
    }
}

@Composable
fun TripNotificationBridge(vm: TrainsterViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = TripNotifications.scope(vm.repository)
    val enabled = TripNotifications.enabled
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(scope, enabled) {
        TripNotifications.schedule(context, vm.repository)
        if (scope != null && enabled && Build.VERSION.SDK_INT >= 33 && !TripNotifications.permissionRequested(context)) {
            TripNotifications.markPermissionRequested(context)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(scope, enabled, lifecycle) {
        if (scope != null && enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val success = try {
                    if (TripNotifications.poll(context, scope) && scope == TripNotifications.scope(vm.repository)) vm.sync()
                    true
                } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                delay(if (success) 10_000 else 30_000)
            }
        }
    }
    val destination = NotificationNavigation.pending
    LaunchedEffect(destination, vm.account?.id) {
        if (destination != null && vm.account != null) {
            if (destination.scope == TripNotifications.scope(vm.repository)) {
                if (vm.trips.none { it.id == destination.tripId }) vm.sync()
                if (destination.scope == TripNotifications.scope(vm.repository) && vm.trips.any { it.id == destination.tripId }) vm.selectedId = destination.tripId
                else vm.message = "Не удалось открыть путешествие. Проверьте подключение и доступ к нему."
            }
            NotificationNavigation.clear()
        }
    }
}

@Composable
fun NotificationSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(TripNotifications.allowed(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = TripNotifications.allowed(context) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = TripNotifications.allowed(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Уведомления о поездках", modifier = Modifier.weight(1f))
            Switch(TripNotifications.enabled, { TripNotifications.setEnabled(context, it) })
        }
        Text("При открытом приложении проверяем изменения каждые 10 секунд. В фоне — примерно раз в 15 минут или реже: время выбирает Android. Сервер должен быть доступен по сети.", style = MaterialTheme.typography.bodySmall)
        if (TripNotifications.enabled && !allowed) TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !TripNotifications.permissionRequested(context)) {
                TripNotifications.markPermissionRequested(context); permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Разрешить уведомления в Android") }
    }
}
