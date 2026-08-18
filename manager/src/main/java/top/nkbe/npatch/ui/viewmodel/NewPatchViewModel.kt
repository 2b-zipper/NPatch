package top.nkbe.npatch.ui.viewmodel

import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import nkbe.util.ModuleMetadataReader
import nkbe.util.ModulePipeline
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.Patcher
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.database.entity.Module
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig

class NewPatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "NewPatchViewModel"

        /** パッチ成功時に自動でスコープ登録するモジュール（Knot） */
        private const val KNOT_PACKAGE_NAME = "app.zipper.knot"
    }

    enum class PatchState {
        INIT, SELECTING, CONFIGURING, PATCHING, FINISHED, ERROR
    }

    enum class InstallMethod {
        SYSTEM, SHIZUKU
    }

    sealed class ViewAction {
        object DoneInit : ViewAction()
        data class ConfigurePatch(val app: AppInfo) : ViewAction()
        object SubmitPatch : ViewAction()
        object LaunchPatch : ViewAction()
    }

    var patchState by mutableStateOf(PatchState.INIT)
        private set

    // Patch Configuration
    @set:JvmName("_setUseManager")
    var useManager by mutableStateOf(true)
        private set
    var newPackageName by mutableStateOf("")
    var debuggable by mutableStateOf(false)
    var overrideVersionCode by mutableStateOf(false)
    var overrideVersionCodeValue by mutableStateOf("1")
    var sigBypassLevel by mutableStateOf(2)
    var injectProvider by mutableStateOf(false)
    var useMicroG by mutableStateOf(true)
    var microgVendor by mutableStateOf("app.revanced")
    var outputLog by mutableStateOf(true)
    var hideLibs by mutableStateOf(false)
    var embeddedModules by mutableStateOf<List<AppInfo>>(emptyList())
    var hasExecutedIntent by mutableStateOf(false)

    lateinit var patchApp: AppInfo
        private set
    lateinit var patchOptions: Patcher.Options
        private set

    val logs = mutableStateListOf<Pair<Int, String>>()
    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                logs += Log.DEBUG to msg
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            logs += Log.INFO to msg
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            logs += Log.ERROR to msg
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.DoneInit -> doneInit()
                is ViewAction.ConfigurePatch -> configurePatch(action.app)
                is ViewAction.SubmitPatch -> submitPatch()
                is ViewAction.LaunchPatch -> launchPatch()
            }
        }
    }

    fun reset() {
        patchState = PatchState.INIT
        useManager = true
        newPackageName = ""
        debuggable = false
        overrideVersionCode = false
        overrideVersionCodeValue = "1"
        sigBypassLevel = 2
        injectProvider = false
        useMicroG = true
        microgVendor = "app.revanced"
        outputLog = true
        hideLibs = false
        embeddedModules = emptyList()
        logs.clear()
        hasExecutedIntent = false
    }

    fun setUseManager(value: Boolean) {
        useManager = value
        if (!value && sigBypassLevel > Constants.SIGBYPASS_HIGH) {
            sigBypassLevel = Constants.SIGBYPASS_HIGH
        }
    }

    private fun doneInit() {
        patchState = PatchState.SELECTING
    }

    private fun configurePatch(app: AppInfo) {
        Log.d(TAG, "Configuring patch for ${app.app.packageName}")
        patchApp = app
        patchState = PatchState.CONFIGURING
        newPackageName = app.app.packageName
    }

    private fun submitPatch() {
        Log.d(TAG, "Submit Patch")
        if (useManager) embeddedModules = emptyList()
        val patchSigBypassLevel = if (useManager) sigBypassLevel else sigBypassLevel.coerceAtMost(Constants.SIGBYPASS_HIGH)
        val patchHideLibs =
            hideLibs &&
                patchSigBypassLevel > Constants.SIGBYPASS_NONE
        val patchVersionCode = overrideVersionCodeValue.toIntOrNull()?.takeIf { it > 0 } ?: 1
        sigBypassLevel = patchSigBypassLevel
        hideLibs = patchHideLibs
        overrideVersionCodeValue = patchVersionCode.toString()
        val config = PatchConfig(
            useManager,
            debuggable,
            overrideVersionCode,
            patchVersionCode,
            patchSigBypassLevel,
            null,
            null,
            injectProvider,
            outputLog,
            newPackageName,
            useMicroG,
            patchHideLibs,
            microgVendor,
        )
        patchOptions = Patcher.Options(
            newPackageName = newPackageName,
            config = config,
            apkPaths = listOf(patchApp.app.sourceDir) + (patchApp.app.splitSourceDirs ?: emptyArray()),
            embeddedModules = embeddedModules.flatMap { listOf(it.app.sourceDir) + (it.app.splitSourceDirs ?: emptyArray()) }
        )
        patchState = PatchState.PATCHING
    }

    private suspend fun launchPatch() {
        logger.i("Launch Patch")
        patchState = try {
            Patcher.patch(logger, patchOptions)
            // このマネージャにはモジュール管理画面が無いため、
            // パッチ成功時に Knot モジュールを自動でスコープ登録する
            runCatching { autoScopeKnot() }
                .onFailure { logger.e("Auto-scope Knot failed: ${it.message}") }
            PatchState.FINISHED
        } catch (t: Throwable) {
            logger.e(t.message.orEmpty())
            logger.e(t.stackTraceToString())
            PatchState.ERROR
        } finally {
            NeoPackageManager.cleanTmpApkDir()
        }
    }

    private suspend fun autoScopeKnot() {
        val pm = lspApp.packageManager
        val knotInfo = runCatching {
            pm.getApplicationInfo(KNOT_PACKAGE_NAME, PackageManager.GET_META_DATA)
        }.getOrNull()
        if (knotInfo == null) {
            logger.i("Knot がインストールされていないため、自動スコープ登録をスキップ")
            return
        }
        val meta = runCatching {
            ModuleMetadataReader.read(
                pm.getPackageInfo(KNOT_PACKAGE_NAME, PackageManager.GET_META_DATA),
                pm,
            )
        }.getOrNull()
        if (meta == null || meta.pipeline == ModulePipeline.UNSUPPORTED) {
            logger.i("Knot は対応していないモジュールのため、自動スコープ登録をスキップ")
            return
        }
        ConfigManager.activateModule(
            patchApp.app.packageName,
            Module(knotInfo.packageName, knotInfo.sourceDir),
        )
        logger.i("Knot を ${patchApp.app.packageName} にスコープ登録しました")
    }
}