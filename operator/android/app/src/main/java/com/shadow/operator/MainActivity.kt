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
import com.shadow.operator.data.TargetStore
import com.shadow.operator.data.RpcClient
import com.shadow.operator.ui.fleet.FleetScreen
import com.shadow.operator.ui.terminal.TerminalScreen
import com.shadow.operator.ui.filemanager.FileManagerScreen
import com.shadow.operator.ui.modules.ModuleManagerScreen
import com.shadow.operator.ui.data.DataScreen
import com.shadow.operator.ui.cap.ContactsScreen
import com.shadow.operator.ui.cap.SmsScreen
import com.shadow.operator.ui.cap.CallLogScreen
import com.shadow.operator.ui.cap.AccessScreen
import com.shadow.operator.ui.theme.ShadowTheme

class MainActivity : ComponentActivity() {
    lateinit var rpcClient: RpcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ClientConfig()
        rpcClient = RpcClient(config)

        // Apply the persisted target BEFORE any screen composes. RpcClient.activeTarget
        // falls back to the identity baked into ClientConfig whenever targetId is blank,
        // and after a reinstall that baked id belongs to a dead implant: every capability
        // screen then queues commands into a target nobody polls and spins on "running"
        // forever, while the home badge -- which reads the store directly -- happily shows
        // the live implant. FleetScreen's LaunchedEffect only runs once its UI exists, so
        // anything opened early (or recomposed without it) inherited the dead default.
        TargetStore(this).selectedId().takeIf { it.isNotBlank() }?.let { rpcClient.targetId = it }

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
                onData = { navController.navigate("data") },
                onCapability = { navController.navigate(it) }
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
        composable("contacts") {
            ContactsScreen(rpc = rpc, onNavigateBack = { navController.popBackStack() })
        }
        composable("sms") {
            SmsScreen(rpc = rpc, onNavigateBack = { navController.popBackStack() })
        }
        composable("calllog") {
            CallLogScreen(rpc = rpc, onNavigateBack = { navController.popBackStack() })
        }
        composable("access") {
            AccessScreen(rpc = rpc, onNavigateBack = { navController.popBackStack() })
        }
        composable("modules") {
            ModuleManagerScreen(
                rpc = rpc,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
