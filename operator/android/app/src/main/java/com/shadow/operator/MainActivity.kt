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
import com.shadow.operator.ui.data.DataScreen
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
                    ShadowApp(config, rpcClient)
                }
            }
        }
    }
}

@Composable
fun ShadowApp(config: ClientConfig, rpc: RpcClient) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "fleet") {
        composable("fleet") {
            FleetScreen(
                config = config,
                onTerminal = { navController.navigate("terminal") },
                onImplantSelect = { navController.navigate("terminal") },
                onFileManager = { navController.navigate("files") },
                onModules = { navController.navigate("modules") },
                onData = { navController.navigate("data") }
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
        composable("data") {
            DataScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("modules") {
            ModuleManagerScreen(
                rpc = rpc,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
