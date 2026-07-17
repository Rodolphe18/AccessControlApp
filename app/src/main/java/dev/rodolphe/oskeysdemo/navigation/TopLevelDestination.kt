package dev.rodolphe.oskeysdemo.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.vector.ImageVector
import dev.rodolphe.oskeysdemo.R
import kotlinx.serialization.Serializable

/**
 * The four bottom-bar tabs of the resident app, mirroring the real Oskey app.
 *
 * Routes are serializable objects so navigation is type-safe: a typo becomes a compile error, not a
 * crash at runtime. Each entry carries its own icon and label so the bottom bar is a pure function
 * of this list — adding a tab is a one-line change here.
 */
@Serializable
sealed interface TopLevelDestination {
    @get:StringRes
    val labelRes: Int
    val icon: ImageVector

    @Serializable
    data object Home : TopLevelDestination {
        override val labelRes get() = R.string.tab_home
        override val icon get() = Icons.Filled.Home
    }

    @Serializable
    data object Invitations : TopLevelDestination {
        override val labelRes get() = R.string.tab_invitations
        override val icon get() = Icons.Filled.Group
    }

    @Serializable
    data object History : TopLevelDestination {
        override val labelRes get() = R.string.tab_history
        override val icon get() = Icons.Filled.History
    }

    @Serializable
    data object Menu : TopLevelDestination {
        override val labelRes get() = R.string.tab_menu
        override val icon get() = Icons.Filled.Menu
    }

    companion object {
        val entries = listOf(Home, Invitations, History, Menu)
    }
}
