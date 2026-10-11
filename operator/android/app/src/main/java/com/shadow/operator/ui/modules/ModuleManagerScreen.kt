package com.shadow.operator.ui.modules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.*
import kotlinx.coroutines.launch

data class ModuleInfo(val name: String, val description: String, val builtIn: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleManagerScreen(
    rpc: RpcClient,
    onNavigateBack: () -> Unit
) {
    // Built-in modules — compiled into libshadow.so, always available
    val modules = listOf(
        ModuleInfo("shell", "Execute shell commands on the target", true),
        ModuleInfo("fs_list", "List directory contents", true),
        ModuleInfo("fs_read", "Read file contents", true),
        ModuleInfo("device_info", "Get device information (model, version, etc.)", true),
        ModuleInfo("heartbeat", "Keep-alive ping with device status", true),
        ModuleInfo("a11y", "Accessibility service event capture (keylog, notifications)", true),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Modules",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = BgBlack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Built-in modules — compiled directly into libshadow.so",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
            }
            items(modules) { module ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                module.name,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                module.description,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = OnlineGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
