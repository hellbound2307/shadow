package com.shadow.operator.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.launch

data class TerminalLine(val text: String, val isCommand: Boolean = false, val isError: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    rpc: RpcClient,
    onNavigateBack: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<TerminalLine>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun executeCommand(cmd: String) {
        if (cmd.isBlank() || busy) return
        lines.add(TerminalLine("shadow@target:~$ $cmd", isCommand = true))
        input = ""
        busy = true
        scope.launch {
            val result = rpc.execute("shell", """{"cmd":"$cmd"}""")
            lines.add(TerminalLine(result ?: "ERROR: no response", isError = result?.startsWith("ERROR") == true || result?.startsWith("TIMEOUT") == true))
            busy = false
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        lines.add(TerminalLine("SHADOW Terminal — connected to implant"))
        lines.add(TerminalLine("Type a command and press Enter. Try: id, ls /sdcard, uname -a"))
        lines.add(TerminalLine(""))
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
            // Output
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
                    Text(
                        text = line.text,
                        color = when {
                            line.isCommand -> AccentGreen
                            line.isError -> AccentRed
                            else -> TextPrimary
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // Input bar
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
                        onValueChange = { input = it },
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
                        keyboardActions = KeyboardActions(onSend = {
                            executeCommand(input)
                        }),
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
