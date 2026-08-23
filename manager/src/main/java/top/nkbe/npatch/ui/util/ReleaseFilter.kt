package top.nkbe.npatch.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.repo.KnotRelease

@Composable
fun rememberDisplayedReleases(releases: List<KnotRelease>): List<KnotRelease> {
    val includePrerelease = Configs.includePrereleaseVersions
    return remember(releases, includePrerelease) {
        if (includePrerelease) releases else releases.filter { !it.isPrerelease }
    }
}
