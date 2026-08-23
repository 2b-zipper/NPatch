package top.nkbe.npatch.network.cdn

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.patch.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "ApkCdnService"

private const val CHANNEL_STABLE = "stable"
private const val CHANNEL_PRERELEASE = "prerelease"

private const val PROGRESS_INTERVAL_MS = 80L

private const val ABI_ARM64 = "arm64-v8a"
private const val ABI_ARM32 = "armeabi-v7a"

private const val FALLBACK_DPI_SPLIT = "config.xxhdpi.apk"

private val deviceAbi: String
    get() = if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) ABI_ARM64 else ABI_ARM32

private fun Long.toMbString(): String = String.format(Locale.US, "%.1f", this / (1024.0 * 1024.0))

data class VersionListResult(
    val recommended: Long? = null,
    val versions: List<Long> = emptyList(),
)

class ApkCdnService(
    private val context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val versionsUrl: String = DEFAULT_VERSIONS_URL,
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun fetchAvailableVersions(
        includePrerelease: Boolean = Configs.includePrereleaseVersions,
    ): VersionListResult = withContext(Dispatchers.IO) {
        val targetChannel = if (includePrerelease) CHANNEL_PRERELEASE else CHANNEL_STABLE
        runCatching {
            val request = Request.Builder().url(versionsUrl).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }

                val channels = JSONObject(response.body.string()).getJSONObject("channels")
                val channel = channels.optJSONObject(targetChannel)
                    ?: channels.getJSONObject(CHANNEL_STABLE)

                val versions = channel.optJSONArray("versions")
                    ?.let { array -> (0 until array.length()).map { array.optLong(it, 0L) } }
                    .orEmpty()
                    .filter { it > 0 }
                    .distinct()

                VersionListResult(
                    recommended = channel.optLong("recommended", 0L).takeIf { it > 0 }
                        ?: versions.firstOrNull(),
                    versions = versions,
                )
            }
        }.onFailure {
            Log.w(TAG, "Failed to fetch available versions from $versionsUrl", it)
        }.getOrDefault(VersionListResult())
    }

    suspend fun downloadLineApksForPatcher(
        logger: Logger,
        outputDir: File,
        targetVersionCode: Long? = null,
        fileNames: List<String> = getSplitFilesForDevice(context),
        onProgressUpdate: ((String) -> Unit)? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val notifyCompleted = onProgressUpdate ?: logger::i

        val downloadedFiles = mutableListOf<File>()
        val resolvedVersionCode = targetVersionCode ?: resolveTargetVersionCode(logger)
        val vParam = resolvedVersionCode?.takeIf { it > 0 }?.let { "?v=$it" }.orEmpty()

        logger.i("[CDN] Device: ABI=$deviceAbi (${Build.SUPPORTED_ABIS.joinToString()}), DPI=${context.resources.displayMetrics.densityDpi}")
        logger.i("[CDN] Target Version Code: ${resolvedVersionCode ?: "Latest"}")
        logger.i("[CDN] Connecting to $baseUrl...")

        for ((index, fileName) in fileNames.withIndex()) {
            val prefix = "[CDN] [${index + 1}/${fileNames.size}]"
            val targetFile = File(outputDir, fileName)

            logger.i("$prefix Downloading $fileName...")

            val request = Request.Builder().url("$baseUrl/apk/file/$fileName$vParam").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Failed to download $fileName (HTTP ${response.code})")
                }

                val body = response.body
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var fileBytes = 0L
                        var lastLoggedTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastLoggedTime > PROGRESS_INTERVAL_MS || fileBytes == totalBytes) {
                                lastLoggedTime = now
                                val progress = if (totalBytes > 0) {
                                    val percent = ((fileBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    "$percent% (${fileBytes.toMbString()} / ${totalBytes.toMbString()} MB)"
                                } else {
                                    "(${fileBytes.toMbString()} MB)"
                                }
                                onProgressUpdate?.invoke("$prefix Downloading $fileName... $progress")
                            }
                        }
                    }
                }
            }

            notifyCompleted("$prefix $fileName (${targetFile.length().toMbString()} MB) downloaded successfully.")
            downloadedFiles.add(targetFile)
        }

        downloadedFiles
    }

    private suspend fun resolveTargetVersionCode(logger: Logger): Long? {
        Configs.customLineVersionCodeOrNull?.let { customCode ->
            logger.i("[CDN] Target Version: $customCode (Manual override from Settings ON)")
            return customCode
        }

        val includePrerelease = Configs.includePrereleaseVersions
        val recommended = fetchAvailableVersions(includePrerelease).recommended
        if (recommended != null) {
            val channel = if (includePrerelease) CHANNEL_PRERELEASE else CHANNEL_STABLE
            logger.i("[CDN] Target Version: $recommended (Resolved from line-versions [$channel])")
            return recommended
        }

        logger.i("[CDN] Target Version: Latest (from Cloud CDN)")
        return null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://2ipper.com"
        const val DEFAULT_VERSIONS_URL =
            "https://raw.githubusercontent.com/2b-zipper/line-versions/main/versions.json"

        fun getSplitFilesForDevice(context: Context): List<String> {
            val abiSplit = "config.${deviceAbi.replace('-', '_')}.apk"

            val densityDpi = context.resources.displayMetrics.densityDpi
            val primaryDpiSplit = when {
                densityDpi <= DisplayMetrics.DENSITY_LOW -> "config.ldpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_MEDIUM -> "config.mdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_TV -> "config.tvdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_HIGH -> "config.hdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_XHIGH -> "config.xhdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_XXHIGH -> "config.xxhdpi.apk"
                else -> "config.xxxhdpi.apk"
            }

            return listOf("base.apk", abiSplit, primaryDpiSplit, FALLBACK_DPI_SPLIT).distinct()
        }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
