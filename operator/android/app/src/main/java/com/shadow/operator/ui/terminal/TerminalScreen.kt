package com.shadow.operator.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.fontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
    val context = LocalContext.current
    val sessionId = remember { "shell_$implantId" }

    // Send command on enter
    LaunchedEffect(key1 = input) {
        if (input.isNotBlank() && input.endsWith("\n")) {
            val cmd = input.trim()
            input = ""
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shell: $implantId", color = Color.White) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { outputLines.clear() }) { Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.White) }
                    IconButton(onClick = { input = "clear\n" }) { Icon(Icons.Default.ContentClear, contentDescription = "Clear Screen", tint = Color.White) }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 60.dp)
        ) {
            // Output Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(padding)
                    .padding(bottom = 8.dp)
                    .background(Color(0xFF0A0A0A))
            ) {
                Text(
                    text = outputLines.joinToString("\n"),
                    color = Color(0xFF00FF00),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        // Input Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("$ ", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.ui.text.input.KeyboardActions(onDone = { input += "\n" }),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        containerColor = Color(0xFF1E1E1E),
                        focusedBorderColor = Color.Green,
                        unfocusedBorderColor = Color.DarkGray,
                        textColor = Color.White,
                        placeholderTextColor = Color.Gray
                    ),
                    placeholder = { Text("command...", color = Color.Gray, fontFamily = FontFamily.Monospace) }
                )
            }
        }
    }
}