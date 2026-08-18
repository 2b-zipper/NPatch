package top.nkbe.npatch.ui.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import java.io.File

private const val TAG = "KnotDownloader"

/**
 * Knot APK をバックグラウンドダウンロードして完了後に自動でインストール画面を開く。
 *
 * BroadcastReceiver を使わずコルーチンでポーリングする設計。
 * Android 10+ では BroadcastReceiver から startActivity が制限されているため、
 * suspend 関数内 (= フォアグラウンドのコルーチンコンテキスト) で開く。
 *
 * ユーザーがタブを切り替えてコルーチンがキャンセルされた場合でも
 * DownloadManager のダウンロード自体は継続し、通知タップで手動インストールできる。
 */
class KnotDownloader(private val context: Context) {

    private val dm: DownloadManager? = context.getSystemService()

    /**
     * APK をダウンロードし、完了したら自動でインストールする。
     *
     * この関数は呼び出し元のコルーチンスコープ内で suspend する。
     * ダウンロード完了 or 失敗まで待機し、成功時のみインストールを実行する。
     * Shizuku 有効時はサイレントインストール、無効時はシステムのインストーラーを開く。
     *
     * @param url  ダウンロード URL (GitHub releases など HTTPS)
     * @param fileName  保存ファイル名 (例: "Knot-v2.6.0.apk")
     * @return ダウンロードとインストールが完了したかどうか
     */
    suspend fun downloadAndOpen(url: String, fileName: String): Boolean {
        val dm = dm ?: run {
            Log.e(TAG, "DownloadManager unavailable")
            return false
        }

        // 同名ファイルが既に存在すれば削除して新規取得
        downloadFile(fileName).delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Downloading $fileName")
            // 完了通知を表示; タップ時も自動でインストーラーが開く (フォールバック)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, null, "knot_downloads/$fileName")
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadId = dm.enqueue(request)
        Log.i(TAG, "Enqueued download id=$downloadId file=$fileName")

        // IO スレッドで 1 秒ごとにステータスをポーリング
        val success = withContext(Dispatchers.IO) {
            while (true) {
                delay(1000L)
                val query = DownloadManager.Query().setFilterById(downloadId)
                val status = dm.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (col >= 0) cursor.getInt(col) else null
                }
                Log.d(TAG, "Download id=$downloadId status=$status")
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return@withContext true
                    DownloadManager.STATUS_FAILED     -> {
                        Log.w(TAG, "Download failed id=$downloadId")
                        return@withContext false
                    }
                    null -> {
                        Log.w(TAG, "Download entry disappeared id=$downloadId")
                        return@withContext false
                    }
                    // STATUS_PENDING / STATUS_RUNNING / STATUS_PAUSED -> continue polling
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

        if (success) {
            // Main スレッドから startActivity を呼ぶ (背景起動制限を回避)
            return withContext(Dispatchers.Main) {
                if (ShizukuApi.isReady) {
                    // Shizuku 有効時はシステムのインストール確認を出さずにサイレントインストールする
                    installViaShizuku(fileName)
                } else {
                    openApk(fileName)
                    true
                }
            }
        }
        return false
    }

    /**
     * Shizuku 経由でダウンロード済み APK をサイレントインストールする。
     *
     * @return インストールが成功したかどうか
     */
    private suspend fun installViaShizuku(fileName: String): Boolean {
        val file = downloadFile(fileName)
        if (!file.exists()) {
            Log.w(TAG, "Downloaded file not found: ${file.absolutePath}")
            return false
        }
        val outcome = NeoPackageManager.installApkFile(file, NeoPackageManager.InstallMethod.SHIZUKU)
        Log.i(TAG, "Shizuku install result: $outcome")
        val (success, message) = when (outcome) {
            is NeoPackageManager.InstallOutcome.Completed ->
                (outcome.status == PackageInstaller.STATUS_SUCCESS) to
                    if (outcome.status == PackageInstaller.STATUS_SUCCESS) {
                        context.getString(R.string.patch_install_successfully)
                    } else {
                        context.getString(R.string.patch_install_failed)
                    }
            NeoPackageManager.InstallOutcome.PermissionRequired ->
                false to context.getString(R.string.patch_install_failed)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return success
    }

    private fun openApk(fileName: String) {
        val file = downloadFile(fileName)
        if (!file.exists()) {
            Log.w(TAG, "Downloaded file not found: ${file.absolutePath}")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened APK installer: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open APK installer", e)
        }
    }

    private fun downloadFile(fileName: String): File {
        val dir = File(context.getExternalFilesDir(null), "knot_downloads")
        dir.mkdirs()
        return File(dir, fileName)
    }
}
