package top.nkbe.npatch.ui.page

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.nkbe.npatch.R
import top.nkbe.npatch.repo.KnotRelease
import top.nkbe.npatch.repo.KnotReleaseLoader
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.rememberDisplayedReleases

/**
 * Knot 全リリース一覧画面。
 * GitHub Releases API から取得したリリースを時系列で表示し、
 * 各リリースのダウンロード・変更履歴へのリンクを提供する。
 */
@Composable
fun KnotReleasesScreen(
    navigator: Navigator,
) {
    val context = LocalContext.current
    var releases by remember { mutableStateOf<List<KnotRelease>>(emptyList()) }
    val displayedReleases = rememberDisplayedReleases(releases)
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        val result = KnotReleaseLoader.fetchAllReleases()
        result.fold(
            onSuccess = { releases = it },
            onFailure = { errorMessage = it.message }
        )
        isLoading = false
    }

    NPatchScaffold(
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
        ) {
            SmallTopAppBar(
                title = stringResource(R.string.knot_releases_title),
                color = COUITheme.colorScheme.surface,
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                size = 40.dp,
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.knot_releases_loading),
                                style = COUITheme.textStyles.body2,
                                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.knot_releases_error),
                                style = COUITheme.textStyles.subtitle,
                                color = COUITheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = COUITheme.textStyles.footnote1,
                                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    isLoading = true
                                    errorMessage = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = COUITheme.colorScheme.primary,
                                ),
                            ) {
                                Text(stringResource(R.string.repo_retry))
                            }
                        }
                    }
                }
                displayedReleases.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.knot_releases_empty),
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        itemsIndexed(
                            items = displayedReleases,
                            key = { _, release -> release.tagName ?: release.hashCode().toString() }
                        ) { index, release ->
                            KnotReleaseItem(
                                release = release,
                                isLatest = index == 0,
                            )
                            if (index < displayedReleases.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = COUITheme.colorScheme.outline.copy(alpha = 0.3f),
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnotReleaseItem(
    release: KnotRelease,
    isLatest: Boolean,
) {
    val context = LocalContext.current
    val version = release.version ?: release.tagName ?: return
    val apkAsset = release.assets.firstOrNull { it.name?.endsWith(".apk") == true }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (isLatest) {
                COUITheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                COUITheme.colorScheme.surfaceContainerHigh
            },
        ),
        cornerRadius = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // ヘッダー: バージョン + リリース日
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.knot_release_version, version),
                            style = COUITheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isLatest) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.knot_release_latest),
                                style = COUITheme.textStyles.footnote2,
                                color = COUITheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    release.publishedAt?.let { date ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.knot_release_date, date.take(10)),
                            style = COUITheme.textStyles.footnote2,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // 変更履歴
            release.body?.let { body ->
                Spacer(Modifier.height(10.dp))
                val changelog = body.lines()
                    .filter { it.isNotBlank() }
                    .take(8)
                    .joinToString("\n") { it.trimStart('*', '\r', ' ') }
                Text(
                    text = changelog,
                    style = COUITheme.textStyles.footnote1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ボタン
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (apkAsset != null) {
                    Button(
                        onClick = {
                            apkAsset.browserDownloadUrl?.let { url ->
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        cornerRadius = 12.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.knot_release_download),
                            style = COUITheme.textStyles.body2,
                        )
                    }
                }
                Button(
                    onClick = {
                        release.htmlUrl?.let { url ->
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    cornerRadius = 12.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = Color.Transparent,
                        contentColor = COUITheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.knot_release_changelog),
                        style = COUITheme.textStyles.body2,
                    )
                }
            }
        }
    }
}