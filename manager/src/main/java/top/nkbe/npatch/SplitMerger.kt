package top.nkbe.npatch

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ZipEntryMap
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueType
import top.nkbe.npatch.patch.util.Logger
import java.io.File
import java.io.IOException

/**
 * Merges a split APK set (base + config splits) into a single monolithic APK
 * using REAndroid's [ApkBundle], so the patcher produces one installable APK
 * instead of an APKS archive.
 */
object SplitMerger {

    /**
     * Merges [apkFiles] (base APK first, then split APKs) into [outputApk].
     * Split-specific attributes, `<uses-split>` elements and Play-Store split
     * meta-data are stripped so the merged APK installs as a single package.
     */
    fun mergeToSingleApk(apkFiles: List<File>, outputApk: File, logger: Logger) {
        require(apkFiles.size > 1) { "Expected a split APK set, got ${apkFiles.size} file(s)" }
        val modulesDir = outputApk.parentFile.resolve("split-merge-modules").apply {
            if (!exists() && !mkdirs()) throw IOException("Unable to create merge workspace: $this")
        }
        try {
            // REAndroid loads modules from a directory, naming the base module "base.apk".
            apkFiles.forEach { file ->
                file.copyTo(modulesDir.resolve(file.name), overwrite = true)
            }

            val bundle = ApkBundle().apply {
                setAPKLogger(LoggerAdapter(logger))
                loadApkDirectory(modulesDir)
            }
            val modules = bundle.apkModuleList
            if (modules.isEmpty()) throw IOException("Nothing to merge, empty modules")
            logger.i("Merging ${modules.size} split APKs...")

            val merged = bundle.mergeModules(false).apply {
                setAPKLogger(LoggerAdapter(logger))
                setLoadDefaultFramework(false)
            }

            merged.androidManifest.apply {
                // Remove split-only attributes from manifest and application elements.
                arrayOf(
                    AndroidManifest.ID_isSplitRequired,
                    AndroidManifest.ID_requiredSplitTypes,
                    AndroidManifest.ID_splitTypes,
                ).forEach { id ->
                    applicationElement.removeAttributesWithId(id)
                    manifestElement.removeAttributesWithId(id)
                }
                arrayOf(
                    AndroidManifest.NAME_requiredSplitTypes,
                    AndroidManifest.NAME_splitTypes,
                ).forEach { attrName ->
                    manifestElement.removeAttributeIf { attribute -> attribute.name == attrName }
                }

                // Remove split requirements so the merged APK installs as a single package.
                manifestElement.removeElementsIf { element -> element.name == "uses-split" }
                arrayOf("splitName", "split").forEach { attrName ->
                    manifestElement.removeAttributeIf { attribute -> attribute.name == attrName }
                    applicationElement.removeAttributeIf { attribute -> attribute.name == attrName }
                }

                // Remove Play Store / source-stamp split meta-data.
                applicationElement.removeElementsIf { element ->
                    if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false
                    val nameAttr = element
                        .getAttributes { it.nameId == AndroidManifest.ID_name }
                        .asSequence()
                        .singleOrNull()
                        ?: return@removeElementsIf false
                    val nameValue = nameAttr.valueString ?: return@removeElementsIf false
                    val shouldRemove = when {
                        nameValue == "com.android.dynamic.apk.fused.modules" -> {
                            val valueAttr = element
                                .getAttributes { it.nameId == AndroidManifest.ID_value }
                                .asSequence()
                                .firstOrNull()
                            valueAttr?.valueString == "base"
                        }
                        nameValue.startsWith("com.android.vending.") -> true
                        nameValue.startsWith("com.android.stamp.") -> true
                        else -> false
                    }
                    if (!shouldRemove) return@removeElementsIf false
                    removeSplitMetaResources(merged, element, nameValue)
                    true
                }

                refresh()
            }
            merged.refreshTable()
            merged.refreshManifest()
            applyExtractNativeLibs(merged)

            outputApk.parentFile?.mkdirs()
            merged.writeApk(outputApk)
            logger.i("Merged APK written to ${outputApk.name} (${outputApk.length()} bytes)")
        } finally {
            modulesDir.deleteRecursively()
        }
    }

    private class LoggerAdapter(private val logger: Logger) : APKLogger {
        override fun logMessage(msg: String) = logger.i(msg)
        override fun logError(msg: String, tr: Throwable?) = logger.e(msg)
        override fun logVerbose(msg: String) = logger.d(msg)
    }

    private fun removeSplitMetaResources(module: ApkModule, element: ResXmlElement, nameValue: String) {
        if (nameValue != "com.android.vending.splits") return
        if (!module.hasTableBlock()) return
        val valueAttr = element
            .getAttributes {
                it.nameId == AndroidManifest.ID_value || it.nameId == AndroidManifest.ID_resource
            }
            .asSequence()
            .firstOrNull()
            ?: return
        if (valueAttr.valueType != ValueType.REFERENCE) return

        val table = module.tableBlock
        val resourceEntry = table.getResource(valueAttr.data) ?: return
        val zipEntryMap = module.zipEntryMap
        removeResourceEntryFiles(resourceEntry, zipEntryMap)
        table.refresh()
    }

    private fun removeResourceEntryFiles(resourceEntry: ResourceEntry, zipEntryMap: ZipEntryMap) {
        for (entry in resourceEntry) {
            val resEntry = entry ?: continue
            val resValue = resEntry.resValue ?: continue
            val path = resValue.valueAsString
            if (!path.isNullOrBlank()) {
                zipEntryMap.remove(path)
            }
            resEntry.isNull = true
            val specTypePair: SpecTypePair = resEntry.typeBlock.parentSpecTypePair
            specTypePair.removeNullEntries(resEntry.id)
        }
    }

    private fun applyExtractNativeLibs(module: ApkModule) {
        val value: Boolean? = if (module.hasAndroidManifest()) {
            module.androidManifest.isExtractNativeLibs
        } else {
            null
        }
        module.setExtractNativeLibs(value)
    }
}
