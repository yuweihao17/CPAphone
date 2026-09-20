package com.cpaphone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.cpaphone.ui.authpool.AuthPoolScreen
import com.cpaphone.ui.dashboard.DashboardScreen
import com.cpaphone.ui.playground.PlaygroundScreen
import com.cpaphone.ui.settings.SettingsScreen
import com.cpaphone.ui.theme.CPAphoneTheme
import com.cpaphone.ui.traces.LiveTracesScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    DASHBOARD("仪表盘", Icons.Default.Dashboard),
    AUTH_POOL("凭据池", Icons.Default.VpnKey),
    TRACES("追踪", Icons.Default.Timeline),
    PLAYGROUND("调试", Icons.Default.Chat),
    SETTINGS("设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CPAphoneTheme {
                MainAppScaffold()
            }
        }
    }
}

@Composable
fun MainAppScaffold() {
    var currentTab by remember { mutableStateOf(MainTab.DASHBOARD) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentTab) {
                MainTab.DASHBOARD -> DashboardScreen()
                MainTab.AUTH_POOL -> AuthPoolScreen()
                MainTab.TRACES -> LiveTracesScreen()
                MainTab.PLAYGROUND -> PlaygroundScreen()
                MainTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
