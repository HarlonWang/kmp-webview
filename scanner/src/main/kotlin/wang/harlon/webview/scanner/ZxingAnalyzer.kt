package wang.harlon.webview.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * CameraX 帧分析器：把每帧的 Y 平面亮度喂给 ZXing 解码二维码。
 *
 * - 解码失败静默忽略（每帧都会尝试）。
 * - 同一份内容只回调一次（去重），避免同一个码每帧重复触发。
 * - [stop] 后不再回调，供识别到合法 URL 后立即止住分析。
 *
 * [onDecoded] 在 CameraX 的分析线程上回调，消费方需自行切回主线程。
 */
internal class ZxingAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    @Volatile
    private var stopped = false
    private var lastText: String? = null

    fun stop() {
        stopped = true
    }

    override fun analyze(image: ImageProxy) {
        if (stopped) {
            image.close()
            return
        }
        try {
            val text = decode(image)
            if (text != null && text != lastText) {
                lastText = text
                onDecoded(text)
            }
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
