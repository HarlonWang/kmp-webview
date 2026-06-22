package wang.harlon.webview.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import wang.harlon.webview.core.WebViewConfig

internal class FileChooserLauncher(
    private val context: Context,
    private val config: WebViewConfig,
    private val singleDocLauncher: ActivityResultLauncher<Array<String>>,
    private val multiDocLauncher: ActivityResultLauncher<Array<String>>,
    private val cameraLauncher: ActivityResultLauncher<Uri>,
    private val cameraPermissionLauncher: ActivityResultLauncher<String>,
) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var awaitingCameraPermission: Boolean = false

    fun launch(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        finishPending(null)
        val wantsCamera = params.isCaptureEnabled
        val wantsMultiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        return when {
            wantsCamera && config.allowCameraCapture -> {
                pendingCallback = callback
                requestCameraPermissionThenLaunch(params)
            }
            config.allowFileChooser -> {
                pendingCallback = callback
                launchDocumentPicker(params.acceptTypes, wantsMultiple)
            }
            else -> {
                callback.onReceiveValue(null)
                true
            }
        }
    }

    fun onPickerResult(uri: Uri?) {
        finishPending(uri?.let { arrayOf(it) })
    }

    fun onMultiPickerResult(uris: List<Uri>) {
        finishPending(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    fun onCameraResult(success: Boolean) {
        val uri = pendingCameraUri.takeIf { success }
        pendingCameraUri = null
        finishPending(uri?.let { arrayOf(it) })
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (!awaitingCameraPermission) return
        awaitingCameraPermission = false
        if (granted) {
            launchCameraIntent()
        } else {
            finishPending(null)
        }
    }

    private fun requestCameraPermissionThenLaunch(params: WebChromeClient.FileChooserParams): Boolean {
        val declared = isCameraDeclaredInManifest()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            granted -> launchCameraIntent()
            declared -> {
                awaitingCameraPermission = true
                runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    .onFailure {
                        Log.w(TAG, "launch camera permission request failed", it)
                        awaitingCameraPermission = false
                        finishPending(null)
                    }
                    .isSuccess
            }
            // CAMERA 未声明：相机路径不可用。若 allowFileChooser 也关闭，遵守开关直接 deny；
            // 否则降级到普通文档选择，给 H5 一个最终结果，避免 input 卡死。
            !config.allowFileChooser -> {
                Log.w(TAG, "CAMERA not declared and allowFileChooser is false; deny")
                finishPending(null)
                true
            }
            else -> {
                Log.w(TAG, "CAMERA not declared in manifest; falling back to document picker")
                launchDocumentPicker(params.acceptTypes, wantsMultiple = false)
            }
        }
    }

    private fun launchDocumentPicker(acceptTypes: Array<String>?, wantsMultiple: Boolean): Boolean {
        val mimes = resolveMimeTypes(acceptTypes)
        val launcher = if (wantsMultiple) multiDocLauncher else singleDocLauncher
        val label = if (wantsMultiple) "multi" else "single"
        return runCatching { launcher.launch(mimes) }
            .onFailure {
                Log.w(TAG, "launch $label document picker failed", it)
                finishPending(null)
            }
            .isSuccess
    }

    private fun resolveMimeTypes(acceptTypes: Array<String>?): Array<String> {
        // 防御 WebView 实现差异：部分 ROM 可能把 H5 accept="image/*,application/pdf" 整体作为
        // 一个 entry 传过来，这里再按逗号拆一层（已拆分的 entry 拆出来还是它自己，幂等）。
        val cleaned = acceptTypes
            ?.asSequence()
            ?.flatMap { it.splitToSequence(',') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            // OpenDocument/OpenMultipleDocuments 走 EXTRA_MIME_TYPES，系统只按 MIME 过滤、不认扩展名。
            // H5 里 accept=".docx" 传过来的是扩展名，必须转成 MIME 才能正常筛选，否则系统拿
            // ".docx" 当 MIME 匹配不到任何文件、整列表置灰。无法识别的扩展名退回 */*。
            ?.flatMap { entry -> resolveEntry(entry) }
            ?.distinct()
            ?.toList()
            .orEmpty()
        return if (cleaned.isEmpty()) arrayOf("*/*") else cleaned.toTypedArray()
    }

    private fun resolveEntry(entry: String): Sequence<String> {
        // 已是 MIME（含 '/'，如 image/* 或 application/pdf）：原样保留。
        if (entry.contains('/')) return sequenceOf(entry)
        // 扩展名（.docx / docx）：先查系统表，系统表对 Office 新格式覆盖不稳定，
        // 再查内置兜底表，仍查不到才放开为 */*。
        val ext = entry.removePrefix(".").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: EXTENSION_MIME_FALLBACK[ext]
        return sequenceOf(mime ?: "*/*")
    }

    private fun launchCameraIntent(): Boolean {
        val uri = createCameraImageUri() ?: run {
            finishPending(null)
            return true
        }
        pendingCameraUri = uri
        return runCatching { cameraLauncher.launch(uri) }
            .onFailure {
                Log.w(TAG, "launch camera failed", it)
                pendingCameraUri = null
                finishPending(null)
            }
            .isSuccess
    }

    private fun createCameraImageUri(): Uri? = runCatching {
        val dir = File(context.cacheDir, "kmpwebview").apply { mkdirs() }
        val file = File(dir, "photo_${UUID.randomUUID()}.jpg").apply { createNewFile() }
        val authority = "${context.packageName}.kmpwebview.fileprovider"
        FileProvider.getUriForFile(context, authority, file)
    }.onFailure { Log.w(TAG, "create camera file failed", it) }.getOrNull()

    private fun isCameraDeclaredInManifest(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)

    private fun finishPending(value: Array<Uri>?) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        callback.onReceiveValue(value)
    }

    fun dispose() {
        finishPending(null)
        pendingCameraUri = null
        awaitingCameraPermission = false
    }

    private companion object {
        const val TAG = "KmpWebViewFileChooser"

        // 系统 MimeTypeMap 对 Office 新格式（OOXML）等覆盖不稳定，这里做兜底。
        // 文档类 MIME 取值对齐 H5 侧的 MIME↔扩展名映射表，避免两端理解不一致。
        val EXTENSION_MIME_FALLBACK = mapOf(
            // 文档
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "docm" to "application/vnd.ms-word.document.macroEnabled.12",
            "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "dotm" to "application/vnd.ms-word.template.macroEnabled.12",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "pdf" to "application/pdf",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "rtf" to "application/rtf",
            // 图片
            "apng" to "image/apng",
            "avif" to "image/avif",
            "bmp" to "image/bmp",
            "gif" to "image/gif",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "svg" to "image/svg+xml",
            "tiff" to "image/tiff",
            "ico" to "image/vnd.microsoft.icon",
            "webp" to "image/webp",
            // 视频
            "mp4" to "video/mp4",
            "m4v" to "video/x-m4v",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "ogv" to "video/ogg",
            "avi" to "video/x-msvideo",
            "mkv" to "video/x-matroska",
            "3gp" to "video/3gpp",
            "3g2" to "video/3gpp2",
            "ts" to "video/mp2t",
            "mpeg" to "video/mpeg",
            "mpg" to "video/mpeg",
        )
    }
}
