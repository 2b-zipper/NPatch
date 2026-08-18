package top.nkbe.npatch.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.suqi8.coui.kmp.basic.NavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem
import io.github.suqi8.coui.kmp.basic.Scaffold

/**
 * Main container with Home and Settings tabs using lightweight NavigationBar.
 */
@Composable
fun MainScreen(
    navigator: Navigator,
    selectedTab: Int = MainTab.Home.ordinal,
    onSelectedTabChange: (Int) -> Unit = {},
) {
    val tabs = MainTab.entries
    val safeSelectedTab = selectedTab.coerceIn(0, tabs.lastIndex)
    val stateHolder = rememberSaveableStateHolder()

    @Composable
    fun Page(page: Int) {
        when (tabs[page]) {
            MainTab.Home -> HomeScreen(navigator = navigator, onNavigateToSettings = { onSelectedTabChange(MainTab.Settings.ordinal) })
            MainTab.Settings -> SettingsScreen()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    val selected = safeSelectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelectedTabChange(index) },
                        icon = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        label = stringResource(tab.labelRes),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            stateHolder.SaveableStateProvider(tabs[safeSelectedTab].name) {
                Page(safeSelectedTab)
            }
        }
    }
}
