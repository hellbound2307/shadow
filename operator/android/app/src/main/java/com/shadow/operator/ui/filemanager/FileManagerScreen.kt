package com.shadow.operator.ui.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.Repository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean

data class FileEntry(val name: String, val isDir: Boolean, val size: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    implantId: String,
    repository: Repository,
    onNavigateBack: () -> Unit
) {
    var currentPath by remember { mutableStateOf("/") }
    val files = remember { mutableStateListOf<FileEntry>() }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadDir(path: String) {
        isLoading = true
        scope.launch {
            try {
                val result = repository.sendCommand(
                    implantId = implantId,
                    type = com.shadow.operator.data.Command.CommandType.FS,
                    payload = "{\"action\":\"ls\", \"path\":\"$path\"}"
                ).await()
                
                // Parse JSON: {"action":"ls","path":"/","files":[{"name":"...", "is_dir":true, "size":0}]}
                val json = Json.parseToJsonElement(result).jsonObject
                val fileArray = json["files"]?.jsonArray
                
                files.clear()
                if (path != "/") files.add(FileEntry("..", true, 0))
                
                fileArray?.forEach {
                    val obj = it.jsonObject
                    files.add(FileEntry(
                        name = obj["name"]?.jsonPrimitive?.content ?: "Unknown",
                        isDir = obj["is_dir"]?.jsonPrimitive?.boolean ?: false,
                        size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                    ))
                }
                currentPath = path
            } catch (e: Exception) {
                // Show error snackbar
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDir(currentPath) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("File Manager", color = Color.White)
                        Text(currentPath, color = Color.Gray, fontSize = 12.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { loadDir(currentPath) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White) }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Green)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(files) { file ->
                    FileRow(file) {
                        if (file.isDir) {
                            val nextPath = if (file.name == "..") {
                                currentPath.substringBeforeLast("/").ifEmpty { "/" }
                            } else {
                                if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
                            }
                            loadDir(nextPath)
                        } else {
                            // TODO: Show context menu for files (Download, Delete, Hex View)
                        }
                    }
                    Divider(color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
fun FileRow(file: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDir) Color(0xFFFFD600) else Color.LightGray,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, color = Color.White, fontSize = 16.sp)
            if (!file.isDir) {
                Text(formatSize(file.size), color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}