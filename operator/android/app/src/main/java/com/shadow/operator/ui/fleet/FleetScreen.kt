package com.shadow.operator.ui.fleet

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.ImplantEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen(
    implants: List<ImplantEntity>,
    connectionState: String,
    onImplantSelect: (ImplantEntity) -> Unit,
    onAddImplantClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("SHADOW Fleet", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Status: $connectionState", 
                            color = if(connectionState == "CONNECTED") Color.Green else Color.Red,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
                actions = {
                    IconButton(onClick = onAddImplantClick) {
                        Icon(Icons.Default.Add, contentDescription = "Add Implant", tint = Color.White)
                    }
                    IconButton(onClick = { /* Refresh */ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (implants.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No implants found. Deploy SHADOW.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(implants, key = { it.implantId }) { implant ->
                    ImplantCard(implant, onClick = { onImplantSelect(implant) })
                }
            }
        }
    }
}

@Composable
fun ImplantCard(implant: ImplantEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (implant.isOnline) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status",
                        tint = if (implant.isOnline) Color(0xFF00E676) else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = implant.nickname ?: implant.implantId.take(8).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                
                // Battery Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val batteryColor = when {
                        implant.batteryLevel > 50 -> Color.Green
                        implant.batteryLevel > 20 -> Color.Yellow
                        else -> Color.Red
                    }
                    Text("${implant.batteryLevel}%", color = batteryColor, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.BatteryFull, contentDescription = "Battery", tint = batteryColor, modifier = Modifier.size(16.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Subtitle: Model + Network
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${implant.model ?: "Unknown Device"} • Android ${implant.androidVersion ?: "?"}",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Text(
                    text = "${implant.networkType ?: "Offline"} • ${implant.countryCode ?: "??"}",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Capability Badges
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CapabilityBadge("Root", implant.rootStatus > 1, Color(0xFFE53935))
                CapabilityBadge("Shizuku", implant.shizukuStatus, Color(0xFF1E88E5))
                
                val modCount = implant.installedModules.count { it == ',' } + if(implant.installedModules.length > 2) 1 else 0
                CapabilityBadge("$modCount Modules", modCount > 0, Color(0xFF8E24AA))
            }
        }
    }
}

@Composable
fun CapabilityBadge(text: String, active: Boolean, activeColor: Color) {
    Surface(
        color = if (active) activeColor.copy(alpha = 0.2f) else Color.DarkGray.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(
            text = text,
            color = if (active) activeColor else Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}