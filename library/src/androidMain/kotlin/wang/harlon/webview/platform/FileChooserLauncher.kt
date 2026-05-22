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
    private val pickerLauncher: ActivityResultLauncher<String>,
    private val cameraLauncher: ActivityResultLauncher<Uri>,
    private val cameraPermissionLauncher: ActivityResultLauncher<String>,
) {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var awaitingCameraPermission: Boolean = false

    fun launch(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        finishPending(null)
        val wantsCamera = params.isCaptureEnabled
        return when {
            wantsCamera && config.allowCameraCapture -> {
                pendingCallback = callback
                requestCameraPermissionThenLaunch()
            }
            config.allowFileChooser -> {
                pendingCallback = callback
                val accept = params.acceptTypes.firstOrNull().takeUnless { it.isNullOrBlank() } ?: "*/*"
                runCatching { pickerLauncher.launch(accept) }
                    .onFailure {
                        Log.w(TAG, "launch picker failed", it)
                        finishPending(null)
                    }
                    .isSuccess
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

    private fun requestCameraPermissionThenLaunch(): Boolean {
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
            // 业务方未在 manifest 声明 CAMERA：相机路径不可用，降级到普通文件选择，
            // 让 H5 input 至少能拿到一次结果，不卡死
            else -> {
                Log.w(TAG, "CAMERA not declared in manifest; falling back to file picker")
                val accept = "image/*"
                runCatching { pickerLauncher.launch(accept) }
                    .onFailure {
                        Log.w(TAG, "launch picker fallback failed", it)
                        finishPending(null)
                    }
                    .isSuccess
            }
        }
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
