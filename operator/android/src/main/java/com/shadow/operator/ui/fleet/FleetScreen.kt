package com.shadow.operator.ui.fleet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class Implant(val id: String, val status: String, val battery: Int, val network: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen() {
    // Mock data - will be populated via WebSocket to Cloudflare Worker
    val implants = listOf(
        Implant("oppo-a16s-7x9k2", "ONLINE", 84, "Wi-Fi"),
        Implant("pixel-6a-2m4p9", "OFFLINE", 12, "LTE"),
        Implant("galaxy-s22-9v1c4", "ONLINE", 100, "VPN")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SHADOW Fleet", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)
        ) {
            items(implants) { implant ->
                ImplantCard(implant)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun ImplantCard(implant: Implant) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(implant.id, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Badge(
                    containerColor = if (implant.status == "ONLINE") Color(0xFF00C853) else Color.Red
                ) {
                    Text(implant.status)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🔋 ${implant.battery}%", color = Color.LightGray)
                Text("🌐 ${implant.network}", color = Color.LightGray)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { /* Open Terminal */ }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                    Text(">_ Shell")
                }
                Button(onClick = { /* Open FS */ }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                    Text("📁 FS")
                }
                Button(onClick = { /* Kill */ }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000))) {
                    Text("☠️ KILL")
                }
            }
        }
    }
}
