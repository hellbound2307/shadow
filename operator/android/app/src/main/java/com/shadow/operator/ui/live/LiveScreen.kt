package com.shadow.operator.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.theme.AccentGreen

// TEMPORARY BISECT BUILD: body gutted to isolate whether the ComposerImpl
// group-stack underflow originates inside this screen's content at all.
@Composable
fun LiveScreen(rpc: RpcClient, onNavigateBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp)
    ) {
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
            Text("Live Screen", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Text("BISECT BUILD: body removed", color = Color.Gray, fontSize = 12.sp)
    }
}
