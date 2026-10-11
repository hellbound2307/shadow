package com.shadow.operator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import com.shadow.operator.data.ClientConfig
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.fleet.FleetScreen
import com.shadow.operator.ui.terminal.TerminalScreen
import com.shadow.operator.ui.filemanager.FileManagerScreen
import com.shadow.operator.ui.modules.ModuleManagerScreen
import com.shadow.operator.ui.theme.ShadowTheme

class MainActivity : ComponentActivity() {
    lateinit var rpcClient: RpcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ClientConfig()
        rpcClient = RpcClient(config)

        setContent {
            ShadowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShadowApp(rpcClient)
                }
            }
        }
    }
}

@Composable
fun ShadowApp(rpc: RpcClient) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "fleet") {
        composable("fleet") {
            FleetScreen(
                rpc = rpc,
                onImplantSelect = { navController.navigate("terminal") },
                onFileManager = { navController.navigate("files") },
                onModules = { navController.navigate("modules") }
            )
        }
        composable("terminal") {
            TerminalScreen(
                rpc = rpc,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("files") {
            FileManagerScreen(
                rpc = rpc,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("modules") {
            ModuleManagerScreen(
                rpc = rpc,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
