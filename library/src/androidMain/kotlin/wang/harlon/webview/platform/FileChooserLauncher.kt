package wang.harlon.webview.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
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
        val cleaned = acceptTypes
            ?.mapNotNull { it.takeIf { s -> s.isNotBlank() }?.trim() }
            ?.distinct()
            .orEmpty()
        return if (cleaned.isEmpty()) arrayOf("*/*") else cleaned.toTypedArray()
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
    }
}
