package dev.rodolphe.syeksodemo.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.rodolphe.syeksodemo.R
import dev.rodolphe.syeksodemo.feature.home.HomeRoute
import dev.rodolphe.syeksodemo.feature.sharing.SharingRoute

/**
 * The signed-in shell: a bottom bar over a [NavHost], one tab per [TopLevelDestination].
 *
 * Each tab's content is still a placeholder — the real screens live in :feature modules and get
 * swapped in as they land. Keeping the shell here means the navigation graph exists and is
 * demonstrable from step 0, before any feature is written.
 */
@Composable
fun SyeksoNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.hasRoute(destination::class) }
                        ?: false
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination) {
                                // Standard bottom-bar behaviour: keep a single instance per tab,
                                // pop back to the start so the back button leaves the app rather
                                // than walking through previously visited tabs.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<TopLevelDestination.Home> { HomeRoute() }
            composable<TopLevelDestination.Invitations> { SharingRoute() }
            composable<TopLevelDestination.History> { PlaceholderScreen(R.string.placeholder_history) }
            composable<TopLevelDestination.Menu> { PlaceholderScreen(R.string.placeholder_menu) }
        }
    }
}

@Composable
private fun PlaceholderScreen(labelRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(labelRes))
    }
}
