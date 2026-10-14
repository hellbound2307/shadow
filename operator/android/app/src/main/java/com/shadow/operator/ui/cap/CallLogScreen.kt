package com.shadow.operator.ui.cap

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private data class Call(
    val number: String,
    val date: String,
    val type: String,
    val duration: String,
)

private val TYPES = mapOf(
    "1" to ("Received" to CapGreen),
    "2" to ("Dialled" to CapBlue),
    "3" to ("Missed" to CapRed),
    "4" to ("Voicemail" to CapPurple),
    "5" to ("Rejected" to CapAmber),
    "6" to ("Blocked" to CapAmber),
    "7" to ("Answered externally" to CapDim),
)

/**
 * Call log with type filters.
 *
 * Projection deliberately excludes cached_name: ColorOS rejects that column and
 * the whole query fails, which is what this screen used to show.
 */
@Composable
fun CallLogScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val load = remember { CapLoad("calllog") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) { load.run(rpc) }

    val filters = listOf("All", "Missed", "Received", "Dialled")

    val all: List<Call> = remember(load.raw) {
        load.table().rows.mapNotNull { r ->
            if (r.isEmpty()) return@mapNotNull null
            Call(
                r.getOrElse(0) { "" }.ifBlank { "private" },
                r.getOrElse(1) { "" },
                r.getOrElse(2) { "" },
                r.getOrElse(3) { "" },
            )
        }
    }

    val shown = remember(all, query, filter) {
        all.filter { c ->
            when (filter) {
                "Missed" -> c.type == "3"
                "Received" -> c.type == "1"
                "Dialled" -> c.type == "2"
                else -> true
            } && (query.isBlank() || c.number.contains(query, true))
        }
    }

    val missed = remember(all) { all.count { it.type == "3" } }

    CapabilityScreen(
        title = "Call Log",
        subtitle = if (load.error != null) "error"
        else "${shown.size} of ${all.size} calls · $missed missed · ${load.tookMs} ms",
        onBack = onNavigateBack,
        loading = load.loading,
        onRefresh = { scope.launch { load.run(rpc) } },
        accent = CapAmber,
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

        CapFilters(filters, filter) { filter = it }
        CapSearch(query, { query = it }, "filter by number")

        if (shown.isEmpty() && !load.loading) {
            CapMessage(
                if (all.isEmpty()) "no calls read\n(check READ_CALL_LOG, then refresh)"
                else "nothing matches this filter",
                if (all.isEmpty()) CapAmber else CapFaint
            )
            return@CapabilityScreen
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown) { c ->
                val (label, tone) = TYPES[c.type] ?: (c.type.ifBlank { "?" } to CapDim)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString("${c.number}\t${c.date}\t$label\t${c.duration}s"))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.number, color = CapText, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                        Text(
                            (c.date.toLongOrNull()?.let { FMT.format(Date(it)) } ?: c.date)
                                .ifBlank { "-" },
                            color = CapFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(label, color = tone, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        val secs = c.duration.toLongOrNull() ?: 0L
                        Text(
                            if (secs > 0) "${secs / 60}m ${secs % 60}s" else "0s",
                            color = CapDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                HorizontalDivider(color = CapBorder)
            }
        }
    }
}
