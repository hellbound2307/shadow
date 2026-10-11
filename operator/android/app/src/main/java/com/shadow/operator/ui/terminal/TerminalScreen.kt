package com.shadow.operator.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.launch

data class TerminalLine(
    val text: String,
    val isCommand: Boolean = false,
    val isError: Boolean = false
)

/** Rolling command history, persisted so it survives process death. */
private class CommandHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("shadow_terminal", Context.MODE_PRIVATE)

    fun load(): MutableList<String> =
        (prefs.getString("history", "") ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toMutableList()

    fun save(list: List<String>) {
        prefs.edit().putString("history", list.takeLast(200).joinToString("\n")).apply()
    }

    /** Persist scrollback so a rotate/back-nav doesn't lose the session. */
    fun loadLines(): MutableList<TerminalLine> =
        (prefs.getString("scrollback", "") ?: "")
            .split('\u0001')
            .filter { it.isNotEmpty() }
            .map { raw ->
                val parts = raw.split('\u0002', limit = 3)
                TerminalLine(
                    text = parts.getOrElse(0) { "" },
                    isCommand = parts.getOrElse(1) { "0" } == "1",
                    isError = parts.getOrElse(2) { "0" } == "1"
                )
            }
            .toMutableList()

    fun saveLines(lines: List<TerminalLine>) {
        val enc = lines.takeLast(300)
            .joinToString("\u0001") { l ->
                "${l.text}\u0002${if (l.isCommand) 1 else 0}\u0002${if (l.isError) 1 else 0}"
            }
        prefs.edit().putString("scrollback", enc).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    rpc: RpcClient,
    onNavigateBack: () -> Unit
) {
    val ctx = LocalContext.current
    val history = remember { CommandHistory(ctx) }

    // rememberSaveable for the in-flight input; scrollback itself is persisted
    // to SharedPreferences because it can grow past the Bundle size limit.
    var input by rememberSaveable { mutableStateOf("") }
    val lines = remember { history.loadLines().ifEmpty { mutableStateListOf() } }
    val cmdHistory = remember { history.load() }
    var historyIdx by remember { mutableIntStateOf(-1) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    fun persist() = history.saveLines(lines)

    fun executeCommand(cmd: String) {
        if (cmd.isBlank() || busy) return
        lines.add(TerminalLine("shadow@target:~$ $cmd", isCommand = true))
        cmdHistory.add(cmd)
        history.save(cmdHistory)
        historyIdx = -1
        input = ""
        busy = true
        persist()
        scope.launch {
            val result = rpc.execute("shell", """{"cmd":${jsonString(cmd)}}""")
            val isErr = result == null ||
                result.startsWith("ERROR") ||
                result.startsWith("TIMEOUT")
            lines.add(TerminalLine(result ?: "ERROR: no response", isError = isErr))
            busy = false
            persist()
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        if (lines.isEmpty()) {
            lines.add(TerminalLine("SHADOW Terminal — connected to implant"))
            lines.add(TerminalLine("Type a command and press Enter. Try: id, ls /sdcard, uname -a"))
            lines.add(TerminalLine(""))
            persist()
        }
    }

    fun copyAll() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("shadow-terminal", lines.joinToString("\n") { it.text }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Terminal",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "History",
                            tint = if (showHistory) AccentGreen else TextSecondary
                        )
                    }
                    IconButton(onClick = { copyAll() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all", tint = TextSecondary)
                    }
                    IconButton(onClick = {
                        lines.clear()
                        lines.add(TerminalLine("— scrollback cleared —"))
                        persist()
                    }) {
                        Text("CLR", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showHistory) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(SurfaceDark)
                ) {
                    items(cmdHistory.reversed()) { h ->
                        Text(
                            text = "↺ $h",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickableNoRipple { input = h; showHistory = false }
                        )
                    }
                }
                Divider(color = Color(0xFF222222))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(BgBlack)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(lines) { line ->
                    SelectionContainer {
                        Text(
                            text = line.text,
                            color = when {
                                line.isCommand -> AccentGreen
                                line.isError -> AccentRed
                                else -> TextPrimary
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.clickableNoRipple {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("shadow-line", line.text))
                            }
                        )
                    }
                }
            }

            Surface(
                color = SurfaceDark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$ ",
                        color = if (busy) TextSecondary else AccentGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; historyIdx = -1 },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (busy) "executing..." else "command",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { executeCommand(input) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentGreen
                        ),
                        shape = MaterialTheme.shapes.small,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { executeCommand(input) },
                        enabled = !busy && input.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (input.isNotBlank() && !busy) AccentGreen else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Minimal JSON string escaper — avoids hand-rolled quoting bugs for shell commands. */
internal fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append("\"").toString()
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )