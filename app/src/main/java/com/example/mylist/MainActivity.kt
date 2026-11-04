package com.example.mylist

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ---------- Données ----------

data class TaskItem(
    val text: String,
    val checked: Boolean = false,
    val due: Long? = null,
    val notif: Boolean = false,
    val notifLead: Int = 15,
    val addedAt: Long = 0L
)

data class TaskList(
    val name: String,
    val items: MutableList<TaskItem>,
    val defaultNotifLead: Int = 15,
    val sortMode: Int = 0,
    val sortDesc: Boolean = false
)

// ---------- Stockage 100% local (fichier JSON privé de l'app) ----------

class LocalStore(private val context: Context) {
    private val file = File(context.filesDir, "lists.json")
    private val prefs = context.getSharedPreferences("mylist_settings", Context.MODE_PRIVATE)

    fun customFolderUri(): String? = prefs.getString("folderUri", null)

    fun setCustomFolder(uri: String?) {
        prefs.edit().putString("folderUri", uri).apply()
    }

    fun getTheme(): Int = prefs.getInt("theme", 0)

    fun setTheme(mode: Int) {
        prefs.edit().putInt("theme", mode).apply()
    }

    fun load(): MutableList<TaskList> {
        if (!file.exists()) return mutableListOf()
        val lists = parseLists(file.readText())
        // Sécurité : dédoublonne noms de listes et noms d'éléments
        // pour éviter un crash au démarrage si le fichier contient des doublons
        val seenLists = mutableSetOf<String>()
        for (i in lists.indices) {
            var name = lists[i].name
            var n = 1
            while (name in seenLists) {
                name = "${lists[i].name} ($n)"
                n++
            }
            seenLists.add(name)
            if (name != lists[i].name) lists[i] = lists[i].copy(name = name)

            val seenItems = mutableSetOf<String>()
            val items = lists[i].items
            for (j in items.indices) {
                var t = items[j].text
                var m = 1
                while (t in seenItems) {
                    t = "${items[j].text} ($m)"
                    m++
                }
                seenItems.add(t)
                if (t != items[j].text) items[j] = items[j].copy(text = t)
            }
        }
        return lists
    }

    fun loadFromUri(uri: Uri): MutableList<TaskList>? = try {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().decodeToString()
        }
        if (text.isNullOrBlank()) null else parseLists(text)
    } catch (e: Exception) {
        null
    }

    fun save(lists: List<TaskList>) {
        val json = toJson(lists)
        file.writeText(json)
        // Copie synchronisée dans le dossier choisi (si défini)
        val uriStr = customFolderUri() ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return
            val target = dir.findFile("lists.json")
                ?: dir.createFile("application/json", "lists.json") ?: return
            context.contentResolver.openOutputStream(target.uri)?.use { os ->
                os.write(json.toByteArray())
            }
        } catch (e: Exception) {
            // Dossier indisponible : ignoré, la copie reprendra au prochain enregistrement
        }
    }

    fun saveToUri(uri: Uri, lists: List<TaskList>): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(toJson(lists).toByteArray())
        } != null
    } catch (e: Exception) {
        false
    }

    fun toJson(lists: List<TaskList>): String {
        val arr = JSONArray()
        for (l in lists) {
            val itemsArr = JSONArray()
            for (item in l.items) {
                val obj = JSONObject()
                    .put("text", item.text)
                    .put("checked", item.checked)
                item.due?.let { d -> obj.put("due", d) }
                obj.put("notif", item.notif)
                obj.put("notifLead", item.notifLead)
                obj.put("addedAt", item.addedAt)
                itemsArr.put(obj)
            }
            arr.put(
                JSONObject()
                    .put("name", l.name)
                    .put("items", itemsArr)
                    .put("defaultNotifLead", l.defaultNotifLead)
                    .put("sortMode", l.sortMode)
                    .put("sortDesc", l.sortDesc)
            )
        }
        return arr.toString()
    }

    fun parseLists(text: String): MutableList<TaskList> {
        val result = mutableListOf<TaskList>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val items = mutableListOf<TaskItem>()
            val itemsArr = o.getJSONArray("items")
            for (j in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(j)
                items.add(TaskItem(
                    it.getString("text"),
                    it.getBoolean("checked"),
                    if (it.has("due")) it.getLong("due") else null,
                    it.optBoolean("notif", false),
                    it.optInt("notifLead", 15),
                    it.optLong("addedAt", 0L)
                ))
            }
            result.add(
                TaskList(
                    o.getString("name"),
                    items,
                    o.optInt("defaultNotifLead", 15),
                    o.optInt("sortMode", 0),
                    o.optBoolean("sortDesc", false)
                )
            )
        }
        return result
    }
}

// ---------- Notifications locales (WorkManager : sans permission d'alarme exacte) ----------

fun workName(listName: String, itemText: String) = "mylist|$listName|$itemText"

fun scheduleNotification(context: Context, listName: String, item: TaskItem) {
    if (!item.notif || item.due == null || item.checked) return
    val fireAt = item.due!! - item.notifLead * 60_000L
    val delay = fireAt - System.currentTimeMillis()
    if (delay <= 0) return
    val work = OneTimeWorkRequestBuilder<NotifWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(
            workDataOf(
                "text" to item.text,
                "list" to listName,
                "lead" to item.notifLead
            )
        )
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        workName(listName, item.text), ExistingWorkPolicy.REPLACE, work
    )
}

fun cancelNotification(context: Context, listName: String, itemText: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(listName, itemText))
}

class NotifWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.success()
        val list = inputData.getString("list") ?: ""
        val lead = inputData.getInt("lead", 15)
        val ctx = applicationContext
        val channelId = "mylist_rappels"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Rappels d'échéances",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        // Bouton 1 : « Marquer comme fait » (coche l'élément, même app fermée)
        val markIntent = Intent(ctx, MarkDoneReceiver::class.java).apply {
            putExtra("list", list)
            putExtra("item", text)
        }
        val markPi = PendingIntent.getBroadcast(
            ctx, (list + text).hashCode(), markIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Bouton 2 : « Redéfinir échéance » (ouvre le dialogue de l'élément)
        val remindIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("editList", list)
            putExtra("editItem", text)
            putExtra("openDialog", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val remindPi = PendingIntent.getActivity(
            ctx, text.hashCode(), remindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Toucher la notification : ouvre la liste au niveau de l'élément
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("editList", list)
            putExtra("editItem", text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPi = PendingIntent.getActivity(
            ctx, (list + text).hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(text)
            .setContentText("Échéance dans moins de $lead min (liste « $list »)")
            .setContentIntent(openPi)
            .addAction(0, "Marquer comme fait", markPi)
            .addAction(0, "Redéfinir échéance", remindPi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(text.hashCode(), notif)
        return Result.success()
    }
}

// Reçoit « Marquer comme fait » depuis la notification : coche l'élément
// directement dans le fichier de données (même si l'app est fermée).
class MarkDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val listName = intent.getStringExtra("list") ?: return
        val itemText = intent.getStringExtra("item") ?: return
        val store = LocalStore(context)
        val lists = store.load()
        val target = lists.find { it.name == listName } ?: return
        val idx = target.items.indexOfFirst { it.text == itemText }
        if (idx >= 0) {
            target.items[idx] = target.items[idx].copy(checked = true)
            cancelNotification(context, listName, itemText)
            // Retire la notification affichée
            NotificationManagerCompat.from(context).cancel(itemText.hashCode())
            store.save(lists)
            // Prévient l'app si elle est ouverte, pour rafraîchir l'affichage
            // immédiatement (sinon l'état en mémoire écraserait la coche)
            context.sendBroadcast(
                Intent("com.example.mylist.ITEM_DONE")
                    .setPackage(context.packageName)
                    .putExtra("list", listName)
                    .putExtra("item", itemText)
            )
        }
    }
}

// Tri des éléments d'une liste selon le mode choisi :
// 0 = alphabétique, 1 = date d'échéance, 2 = date d'ajout. desc = ordre inverse.
fun sortForList(items: List<TaskItem>, mode: Int, desc: Boolean): List<TaskItem> =
    when (mode) {
        1 -> items.sortedWith(Comparator { a, b ->
            val ad = a.due
            val bd = b.due
            when {
                ad == null && bd == null -> 0
                ad == null -> 1        // sans échéance : toujours en dernier
                bd == null -> -1
                desc -> bd.compareTo(ad)
                else -> ad.compareTo(bd)
            }
        })
        2 -> {
            val base = compareBy<TaskItem> { it.addedAt }
            if (desc) items.sortedWith(base.reversed()) else items.sortedWith(base)
        }
        else -> {
            val base = compareBy<TaskItem>({ it.text.lowercase() }, { it.text })
            if (desc) items.sortedWith(base.reversed()) else items.sortedWith(base)
        }
    }

// ---------- Activité principale ----------

class MainActivity : ComponentActivity() {

    // Demande reçue d'une notification : (liste, élément, ouvrir le dialogue ?)
    private val pendingEdit = mutableStateOf<Triple<String, String, Boolean>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStore(this)
        handleEditIntent(intent)
        val themeMode = mutableStateOf(store.getTheme())
        setContent {
            // Demande l'autorisation d'afficher des notifications (Android 13+)
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            // Thème : système, clair ou sombre — accent bordeaux
            val dark = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val colors = if (dark) darkColorScheme(
                primary = Color(0xFFFFB2C0),
                onPrimary = Color(0xFF5E0E2B),
                secondary = Color(0xFFE6BDC4),
                primaryContainer = Color(0xFF7A2743),
                onPrimaryContainer = Color(0xFFFFD9DE)
            ) else lightColorScheme(
                primary = Color(0xFF8E1F3F),
                onPrimary = Color.White,
                secondary = Color(0xFF77565D),
                primaryContainer = Color(0xFFFFD9DE),
                onPrimaryContainer = Color(0xFF3F0019)
            )
            MaterialTheme(colorScheme = colors) {
                App(store, pendingEdit, themeMode) { mode ->
                    themeMode.value = mode
                    store.setTheme(mode)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEditIntent(intent)
    }

    private fun handleEditIntent(intent: Intent?) {
        val list = intent?.getStringExtra("editList") ?: return
        val item = intent.getStringExtra("editItem") ?: return
        val openDialog = intent.getBooleanExtra("openDialog", false)
        // Retire la notification cliquée
        NotificationManagerCompat.from(this).cancel(item.hashCode())
        pendingEdit.value = Triple(list, item, openDialog)
    }
}

@Composable
fun App(
    store: LocalStore,
    pendingEdit: MutableState<Triple<String, String, Boolean>?>,
    themeMode: MutableState<Int>,
    onThemeChange: (Int) -> Unit
) {
    val context = LocalContext.current
    var lists by remember { mutableStateOf(store.load()) }
    var openListName by remember { mutableStateOf<String?>(null) }
    var showNewListDialog by remember { mutableStateOf(false) }
    var showCopyDataDialog by remember { mutableStateOf(false) }
    var editRequest by remember { mutableStateOf<String?>(null) }
    var scrollRequest by remember { mutableStateOf<String?>(null) }

    // Recharge les données quand l'app revient au premier plan
    // (ex. après « Marquer comme fait » depuis une notification)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lists = store.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // « Marquer comme fait » depuis la notification pendant que l'app est
    // ouverte : met à jour l'affichage immédiatement (sinon l'état en mémoire
    // écraserait la coche au prochain enregistrement)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val listName = intent.getStringExtra("list") ?: return
                val itemText = intent.getStringExtra("item") ?: return
                lists = lists.map { l ->
                    if (l.name == listName) l.copy(
                        items = l.items.map {
                            if (it.text == itemText) it.copy(checked = true) else it
                        }.toMutableList()
                    ) else l
                }.toMutableList()
            }
        }
        val filter = IntentFilter("com.example.mylist.ITEM_DONE")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Demande venue d'une notification : ouvre la liste, avec le dialogue
    // d'édition (« Redéfinir échéance ») ou un défilement jusqu'à l'élément (tap)
    LaunchedEffect(pendingEdit.value) {
        pendingEdit.value?.let { (listName, itemText, openDialog) ->
            openListName = listName
            if (openDialog) editRequest = itemText else scrollRequest = itemText
            pendingEdit.value = null
        }
    }

    // Le bouton Retour d'Android revient à l'accueil au lieu de fermer l'app
    BackHandler(enabled = openListName != null) { openListName = null }

    fun persist() = store.save(lists)

    val current = openListName?.let { name -> lists.find { it.name == name } }

    if (current != null) {
        ListScreen(
            list = current,
            onBack = { openListName = null },
            onToggle = { item ->
                val toggled = item.copy(checked = !item.checked)
                lists = lists.map { l ->
                    if (l.name == current.name) l.copy(
                        items = l.items.map {
                            if (it.text == item.text) toggled else it
                        }.toMutableList()
                    ) else l
                }.toMutableList()
                persist()
                // Planifie ou annule la notification selon le nouvel état
                if (toggled.notif && toggled.due != null) {
                    if (toggled.checked) cancelNotification(context, current.name, toggled.text)
                    else scheduleNotification(context, current.name, toggled)
                }
            },
            onAddItem = { text, due, notif, lead ->
                val item = TaskItem(text, false, due, notif && due != null, lead, System.currentTimeMillis())
                lists = lists.map { l ->
                    if (l.name == current.name) l.copy(
                        items = (l.items + item).toMutableList()
                    ) else l
                }.toMutableList()
                persist()
                if (item.notif) scheduleNotification(context, current.name, item)
            },
            onDeleteItem = { item ->
                lists = lists.map { l ->
                    if (l.name == current.name) l.copy(
                        items = l.items.filterNot { it.text == item.text }.toMutableList()
                    ) else l
                }.toMutableList()
                persist()
                cancelNotification(context, current.name, item.text)
            },
            onEditItem = { oldText, newText, newDue, newNotif, newLead ->
                cancelNotification(context, current.name, oldText)
                lists = lists.map { l ->
                    if (l.name == current.name) l.copy(
                        items = l.items.map {
                            if (it.text == oldText) it.copy(
                                text = newText,
                                due = newDue,
                                notif = newNotif && newDue != null,
                                notifLead = newLead
                            ) else it
                        }.toMutableList()
                    ) else l
                }.toMutableList()
                persist()
                val updated = lists.first { it.name == current.name }
                    .items.first { it.text == newText }
                if (updated.notif) scheduleNotification(context, current.name, updated)
            },
            onListSettings = { lead ->
                lists = lists.map { l ->
                    if (l.name == current.name) l.copy(defaultNotifLead = lead) else l
                }.toMutableList()
                persist()
            },
            onSortChange = { mode ->
                lists = lists.map { l ->
                    if (l.name == current.name) {
                        if (l.sortMode == mode) l.copy(sortDesc = !l.sortDesc)
                        else l.copy(sortMode = mode, sortDesc = false)
                    } else l
                }.toMutableList()
                persist()
            },
            editRequest = editRequest,
            onEditConsumed = { editRequest = null },
            scrollRequest = scrollRequest,
            onScrollConsumed = { scrollRequest = null }
        )
    } else {
        HomeScreen(
            lists = lists,
            themeMode = themeMode.value,
            onThemeChange = onThemeChange,
            onOpen = { openListName = it },
            onNewList = { showNewListDialog = true },
            onDeleteList = { name ->
                lists.find { it.name == name }?.items?.forEach { item ->
                    if (item.notif) cancelNotification(context, name, item.text)
                }
                lists = lists.filterNot { it.name == name }.toMutableList()
                persist()
            },
            onRenameList = { oldName, newName ->
                lists.find { it.name == oldName }?.items?.forEach { item ->
                    if (item.notif) {
                        cancelNotification(context, oldName, item.text)
                        scheduleNotification(context, newName, item)
                    }
                }
                lists = lists.map {
                    if (it.name == oldName) it.copy(name = newName) else it
                }.toMutableList()
                if (openListName == oldName) openListName = newName
                persist()
            },
            onMoveList = { name, step ->
                val index = lists.indexOfFirst { it.name == name }
                val target = index + step
                if (index >= 0 && target in lists.indices) {
                    val l = lists.toMutableList()
                    val moved = l.removeAt(index)
                    l.add(target, moved)
                    lists = l
                    persist()
                    true
                } else false
            },
            onExport = { uri ->
                store.saveToUri(uri, lists)
            },
            onImport = { uri ->
                val imported = store.loadFromUri(uri)
                if (imported != null && imported.isNotEmpty()) {
                    // Fusion : les listes importées s'ajoutent aux existantes ;
                    // en cas de doublon, on renomme (« Nom (1) », « Nom (2) »…)
                    val names = lists.map { it.name }.toMutableSet()
                    val merged = lists.toMutableList()
                    for (l in imported) {
                        var name = l.name
                        var i = 1
                        while (name in names) {
                            name = "${l.name} ($i)"
                            i++
                        }
                        names.add(name)
                        val newList = if (name == l.name) l else l.copy(name = name)
                        merged.add(newList)
                        newList.items.forEach { item ->
                            if (item.notif) scheduleNotification(context, newList.name, item)
                        }
                    }
                    lists = merged
                    persist()
                }
            },
            onFolderChosen = { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Permission non persistable : ignoré
                }
                store.setCustomFolder(uri.toString())
                if (lists.isNotEmpty()) showCopyDataDialog = true
            }
        )
    }

    if (showNewListDialog) {
        NameDialog(
            title = "Nouvelle liste",
            label = "Nom de la liste",
            exists = { name -> lists.any { it.name == name } },
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    lists = (lists + TaskList(name.trim(), mutableListOf())).toMutableList()
                    persist()
                }
                showNewListDialog = false
            },
            onDismiss = { showNewListDialog = false }
        )
    }

    if (showCopyDataDialog) {
        AlertDialog(
            onDismissRequest = { showCopyDataDialog = false },
            title = { Text("Déplacer vos données ?") },
            text = {
                Text(
                    "Copier vos listes actuelles dans le dossier sélectionné ? " +
                            "Elles y seront synchronisées à chaque modification."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    persist()
                    showCopyDataDialog = false
                }) { Text("Copier") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDataDialog = false }) { Text("Annuler") }
            }
        )
    }
}

// ---------- Écran d'accueil : mes listes ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    lists: List<TaskList>,
    onOpen: (String) -> Unit,
    onNewList: () -> Unit,
    onDeleteList: (String) -> Unit,
    onRenameList: (String, String) -> Unit,
    onMoveList: (String, Int) -> Boolean,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onFolderChosen: (Uri) -> Unit,
    themeMode: Int,
    onThemeChange: (Int) -> Unit
) {
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val dragThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onFolderChosen) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExport) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("tic-task") },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "À propos")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Thème…") },
                            onClick = { showMenu = false; showThemeDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Dossier de sauvegarde…") },
                            onClick = { showMenu = false; folderLauncher.launch(null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Exporter…") },
                            onClick = {
                                showMenu = false
                                exportLauncher.launch("mylist-export.json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Importer…") },
                            onClick = { showMenu = false; importLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp)
        ) {
            itemsIndexed(lists, key = { _, l -> l.name }) { index, list ->
                var showDelete by remember { mutableStateOf(false) }
                var showRename by remember { mutableStateOf(false) }
                val isDragging = draggingKey == list.name

                Card(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .shadow(
                            elevation = if (isDragging) 8.dp else 1.dp,
                            shape = RoundedCornerShape(12.dp)
                        )
                        // La carte suit le doigt pendant le glissement
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffset
                                shadowElevation = 16f
                            }
                        }
                        .clickable { onOpen(list.name) }
                        // Réorganisation : appui long puis glisser
                        .pointerInput(list.name) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = list.name
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val step = when {
                                        dragOffset > dragThreshold -> 1
                                        dragOffset < -dragThreshold -> -1
                                        else -> 0
                                    }
                                    if (step != 0) {
                                        val moved = draggingKey?.let { onMoveList(it, step) } ?: false
                                        if (moved) dragOffset -= dragThreshold * step
                                    }
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                }
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            val done = list.items.count { it.checked }
                            Text(
                                "$done / ${list.items.size} fait",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showRename = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Renommer",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (showDelete) {
                    AlertDialog(
                        onDismissRequest = { showDelete = false },
                        title = { Text("Supprimer « ${list.name} » ?") },
                        confirmButton = {
                            TextButton(onClick = { onDeleteList(list.name); showDelete = false }) {
                                Text("Supprimer")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDelete = false }) { Text("Annuler") }
                        }
                    )
                }
                if (showRename) {
                    NameDialog(
                        title = "Renommer la liste",
                        label = "Nom de la liste",
                        initial = list.name,
                        exists = { newName -> lists.any { it.name == newName } },
                        onConfirm = { newName ->
                            if (newName.isNotBlank() && newName != list.name) {
                                onRenameList(list.name, newName.trim())
                            }
                            showRename = false
                        },
                        onDismiss = { showRename = false }
                    )
                }
            }
            // Dernier élément : bouton pour créer une nouvelle liste
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onNewList() },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF8E1F3F),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "➕  Nouvelle liste",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("tic-task") },
            text = {
                Column {
                    Text("tic-task 1.0 — 4 novembre 2026", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Date de sortie : 4 novembre 2026")
                    Text(
                        "Application de listes de tâches 100 % locale : " +
                                "aucune donnée ne quitte votre téléphone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("OK") }
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Thème de l'application") },
            text = {
                Column {
                    ThemeOption("Système (par défaut)", themeMode == 0) {
                        onThemeChange(0); showThemeDialog = false
                    }
                    ThemeOption("Clair", themeMode == 1) {
                        onThemeChange(1); showThemeDialog = false
                    }
                    ThemeOption("Sombre", themeMode == 2) {
                        onThemeChange(2); showThemeDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Annuler") }
            }
        )
    }
}

// ---------- Options de tri et de thème (lignes sélectionnables) ----------

@Composable
fun SortOption(label: String, selected: Boolean, desc: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f))
        if (selected) {
            Text(
                if (desc) "↓" else "↑",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

// ---------- Écran d'une liste : les éléments ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    list: TaskList,
    onBack: () -> Unit,
    onToggle: (TaskItem) -> Unit,
    onAddItem: (String, Long?, Boolean, Int) -> Unit,
    onDeleteItem: (TaskItem) -> Unit,
    onEditItem: (String, String, Long?, Boolean, Int) -> Unit,
    onListSettings: (Int) -> Unit,
    onSortChange: (Int) -> Unit,
    editRequest: String? = null,
    onEditConsumed: () -> Unit = {},
    scrollRequest: String? = null,
    onScrollConsumed: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<TaskItem?>(null) }
    var deleteItem by remember { mutableStateOf<TaskItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Ouvre le dialogue d'édition quand on vient d'une notification
    LaunchedEffect(editRequest) {
        if (editRequest != null) {
            list.items.find { it.text == editRequest }?.let { editItem = it }
            onEditConsumed()
        }
    }

    val todo = sortForList(list.items.filter { !it.checked }, list.sortMode, list.sortDesc)
    val done = sortForList(list.items.filter { it.checked }, list.sortMode, list.sortDesc)

    // Fait défiler la liste jusqu'à l'élément (tap sur la notification)
    LaunchedEffect(scrollRequest) {
        if (scrollRequest != null) {
            val todoIdx = todo.indexOfFirst { it.text == scrollRequest }
            val doneIdx = done.indexOfFirst { it.text == scrollRequest }
            val position = when {
                todoIdx >= 0 -> 1 + todoIdx
                doneIdx >= 0 -> todo.size + 2 + doneIdx
                else -> -1
            }
            if (position >= 0) listState.scrollToItem(position)
            onScrollConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showSortDialog = true }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                "Tri croissant",
                                modifier = Modifier.size(18.dp)
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                "Tri décroissant",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Options de la liste"
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Ajouter un élément"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp)
        ) {
            item {
                Text(
                    "À faire",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                )
            }
            itemsIndexed(todo, key = { _, item -> item.text }) { _, item ->
                EntryRow(Modifier.animateItem(), item, onToggle,
                    onEdit = { editItem = item }, onDelete = { deleteItem = item })
            }
            if (done.isNotEmpty()) {
                item {
                    Text(
                        "Effectués",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp)
                    )
                }
                itemsIndexed(done, key = { _, item -> item.text }) { _, item ->
                    EntryRow(Modifier.animateItem(), item, onToggle,
                        onEdit = { editItem = item }, onDelete = { deleteItem = item })
                }
            }
            item {
                TextButton(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("➕  Ajouter un élément")
                }
            }
        }
    }

    if (showAddDialog) {
        EntryDialog(
            initialNotifLead = list.defaultNotifLead,
            exists = { t -> list.items.any { it.text == t } },
            onConfirm = { text, due, notif, lead ->
                if (text.isNotBlank()) onAddItem(text.trim(), due, notif, lead)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editItem?.let { item ->
        EntryDialog(
            initialText = item.text,
            initialDue = item.due,
            initialNotif = item.notif,
            initialNotifLead = item.notifLead,
            exists = { t -> list.items.any { it.text == t } },
            onConfirm = { text, due, notif, lead ->
                if (text.isNotBlank()) onEditItem(item.text, text.trim(), due, notif, lead)
                editItem = null
            },
            onDismiss = { editItem = null }
        )
    }

    if (showSettings) {
        NameDialog(
            title = "Notification par défaut",
            label = "Minutes avant l'échéance",
            initial = list.defaultNotifLead.toString(),
            onConfirm = { value ->
                value.toIntOrNull()?.let { onListSettings(it.coerceIn(0, 10080)) }
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Trier par") },
            text = {
                Column {
                    SortOption("Alphabétique", list.sortMode == 0, list.sortDesc) { onSortChange(0) }
                    SortOption("Date d'échéance", list.sortMode == 1, list.sortDesc) { onSortChange(1) }
                    SortOption("Date d'ajout", list.sortMode == 2, list.sortDesc) { onSortChange(2) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) { Text("Fermer") }
            }
        )
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("Supprimer « ${item.text} » ?") },
            confirmButton = {
                TextButton(onClick = { onDeleteItem(item); deleteItem = null }) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) { Text("Annuler") }
            }
        )
    }
}

// Une ligne d'élément : case à cocher, nom cliquable (pour modifier), échéance éventuelle, croix
@Composable
fun EntryRow(
    modifier: Modifier = Modifier,
    item: TaskItem,
    onToggle: (TaskItem) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { onToggle(item) }
        )
        Text(
            item.text,
            modifier = Modifier
                .weight(1f)
                .clickable { onEdit() },
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.checked)
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.onSurface
        )
        if (item.due != null) {
            Text(
                formatDue(item.due!!),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.checked)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable { onEdit() }
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Supprimer",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
    HorizontalDivider()
}

// ---------- Boîte de dialogue de saisie (listes) ----------

@Composable
fun NameDialog(
    title: String,
    label: String,
    initial: String = "",
    exists: (String) -> Boolean = { false },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    // Doublon : nom différent de l'original mais déjà utilisé ailleurs
    val duplicate = text.isNotBlank() &&
            text.trim() != initial.trim() &&
            exists(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                isError = duplicate,
                supportingText = {
                    if (duplicate) {
                        Text(
                            "Ce nom existe déjà",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = !duplicate
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

// ---------- Boîte de dialogue d'un élément : texte + échéance optionnelle ----------

fun formatDue(millis: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(millis))

fun defaultDueToday(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDialog(
    initialText: String = "",
    initialDue: Long? = null,
    initialNotif: Boolean = false,
    initialNotifLead: Int = 15,
    exists: (String) -> Boolean = { false },
    onConfirm: (String, Long?, Boolean, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var hasDue by remember { mutableStateOf(initialDue != null) }
    var due by remember { mutableStateOf(initialDue) }
    var hasNotif by remember { mutableStateOf(initialNotif) }
    var notifLeadText by remember { mutableStateOf(initialNotifLead.toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf(0L) }
    val duplicate = text.isNotBlank() &&
            text.trim() != initialText.trim() &&
            exists(text.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialText.isEmpty()) "Nouvel élément" else "Modifier l'élément")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Texte de l'élément") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = {
                        if (duplicate) {
                            Text(
                                "Ce nom existe déjà",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = hasDue,
                        onCheckedChange = { checked ->
                            hasDue = checked
                            if (checked && due == null) due = defaultDueToday()
                            if (!checked) { due = null; hasNotif = false }
                        }
                    )
                    Text("Échéance")
                }
                if (hasDue) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text("📅 " + formatDue(due ?: defaultDueToday()))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = hasNotif,
                            onCheckedChange = { hasNotif = it }
                        )
                        Text("Notification")
                    }
                    if (hasNotif) {
                        OutlinedTextField(
                            value = notifLeadText,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit) && value.length <= 4) notifLeadText = value
                            },
                            label = { Text("Minutes avant l'échéance") },
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lead = notifLeadText.toIntOrNull() ?: initialNotifLead
                    onConfirm(text, if (hasDue) due else null, hasDue && hasNotif, lead)
                },
                enabled = !duplicate
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )

    // Étape 1 : choix de la date
    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = due ?: defaultDueToday()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDate = dateState.selectedDateMillis ?: System.currentTimeMillis()
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Suivant") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Annuler") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // Étape 2 : choix de l'heure
    if (showTimePicker) {
        val initCal = Calendar.getInstance().apply {
            timeInMillis = due ?: defaultDueToday()
        }
        val timeState = rememberTimePickerState(
            initialHour = initCal.get(Calendar.HOUR_OF_DAY),
            initialMinute = initCal.get(Calendar.MINUTE)
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Heure") },
            text = { TimeInput(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = pendingDate
                    }
                    val cal = Calendar.getInstance().apply {
                        set(
                            utc.get(Calendar.YEAR), utc.get(Calendar.MONTH),
                            utc.get(Calendar.DAY_OF_MONTH),
                            timeState.hour, timeState.minute, 0
                        )
                    }
                    due = cal.timeInMillis
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Annuler") }
            }
        )
    }
}