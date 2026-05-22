package wang.harlon.webview.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.PermissionRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

internal class MediaPermissionLauncher(
    private val context: Context,
    private val systemPermissionLauncher: ActivityResultLauncher<Array<String>>,
) {
    private var pendingRequest: PermissionRequest? = null

    fun handle(request: PermissionRequest) {
        // 同时只允许一个 pending PermissionRequest：若已有上一个未决，先 deny 旧的，避免 H5 端
        // Promise 永远不 resolve。
        pendingRequest?.let { previous ->
            pendingRequest = null
            previous.deny()
        }
        val webResources = request.resources ?: emptyArray()
        val systemPerms = webResources.mapNotNull(::mapToSystemPermission).distinct()
        if (systemPerms.isEmpty()) {
            request.deny()
            return
        }
        val ungranted = systemPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isEmpty()) {
            request.grant(webResources)
            return
        }
        pendingRequest = request
        runCatching { systemPermissionLauncher.launch(ungranted.toTypedArray()) }
            .onFailure {
                Log.w(TAG, "request system permission failed", it)
                pendingRequest = null
                request.deny()
            }
    }

    fun onSystemPermissionResult(result: Map<String, Boolean>) {
        val request = pendingRequest ?: return
        pendingRequest = null
        if (result.values.all { it }) {
            request.grant(request.resources)
        } else {
            request.deny()
        }
    }

    fun dispose() {
        pendingRequest?.deny()
        pendingRequest = null
    }

    private fun mapToSystemPermission(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private companion object {
        const val TAG = "KmpWebViewMediaPerm"
    }
}
