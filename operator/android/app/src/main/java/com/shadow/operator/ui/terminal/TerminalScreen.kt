package com.shadow.operator.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    implantId: String,
    repository: Repository,
    onNavigateBack: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val outputLines = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sessionId = remember { "shell_$implantId" }

    fun executeCommand(cmd: String) {
        if (cmd.isBlank()) return
        outputLines.add("$ $cmd")
        scope.launch {
            try {
                val result = repository.sendCommand(
                    implantId = implantId,
                    type = com.shadow.operator.data.Command.CommandType.SHELL,
                    payload = "{\"cmd\":\"$cmd\"}",
                    sessionId = sessionId
                ).await()
                outputLines.add(result)
            } catch (e: Exception) {
                outputLines.add("[ERROR] ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shell: $implantId", color = Color.White) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { outputLines.clear() }) { Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.White) }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Output Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .background(Color(0xFF0A0A0A))
                    .padding(8.dp)
            ) {
                Text(
                    text = outputLines.joinToString("\n"),
                    color = Color(0xFF00FF00),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$ ", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Text
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            executeCommand(input)
                            input = ""
                        }
                    ),
                    placeholder = { Text("command...", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                )
            }
        }
    }
}
