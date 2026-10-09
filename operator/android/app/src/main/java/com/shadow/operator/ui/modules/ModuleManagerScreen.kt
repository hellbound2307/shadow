package com.shadow.operator.ui.modules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.Repository
import com.shadow.operator.data.ModuleRegistryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleManagerScreen(
    implantId: String,
    repository: Repository,
    onNavigateBack: () -> Unit
) {
    val modules by repository.moduleRegistry.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Module Manager", color = Color.White) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color.Black
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mock module list - will sync from Cloudflare R2
            val available = listOf(
                ModuleRegistryEntity(1, "shadow-shell", "1.0.0", "Provides PTY shell access", "[]", 21, 512000, "", "", true, 0),
                ModuleRegistryEntity(2, "shadow-fs", "1.0.0", "Filesystem management", "[]", 21, 350000, "", "", true, 0),
                ModuleRegistryEntity(3, "shadow-binder", "1.0.0", "IPC tracing and injection", "[\"shizuku\"]", 24, 450000, "", "", false, 0),
                ModuleRegistryEntity(4, "shadow-fridge", "1.0.0", "Frida-Gadget memory loader", "[\"ptrace\"]", 24, 1200000, "", "", false, 0),
                ModuleRegistryEntity(5, "shadow-crypto", "1.0.0", "Keystore extraction", "[]", 28, 250000, "", "", false, 0)
            )

            items(available) { module ->
                ModuleCard(module) {
                    // Deploy logic
                }
            }
        }
    }
}

@Composable
fun ModuleCard(module: ModuleRegistryEntity, onDeploy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(module.moduleName, color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(containerColor = if (module.isLoaded) Color.Green else Color.DarkGray) {
                        Text(if (module.isLoaded) "LOADED" else "UNLOADED")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("v${module.version} • ${module.sizeBytes / 1024} KB", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(module.description, color = Color.LightGray, fontSize = 12.sp)
            }
            IconButton(onClick = onDeploy) {
                if (module.isLoaded) {
                    Icon(Icons.Default.Delete, contentDescription = "Unload", tint = Color.Red)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = "Deploy", tint = Color(0xFFAA00FF))
                }
            }
        }
    }
}