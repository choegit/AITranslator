package com.example.aitranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.aitranslator.ui.conversation.ConversationScreen
import com.example.aitranslator.ui.models.ModelManagementScreen
import com.example.aitranslator.ui.theme.AITranslatorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AITranslatorTheme {
                ConverseApp()
            }
        }
    }
}

private object Routes {
    const val CONVERSATION = "conversation"
    const val MODELS = "models"
}

@Composable
private fun ConverseApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CONVERSATION) {
        composable(Routes.CONVERSATION) {
            ConversationScreen(onOpenModels = { navController.navigate(Routes.MODELS) })
        }
        composable(Routes.MODELS) {
            ModelManagementScreen(onBack = { navController.popBackStack() })
        }
    }
}
