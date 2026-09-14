package com.shantanu.shield.ui.parent

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.shantanu.shield.remote.QrScanner

/**
 * CameraX adapter for the pure [QrScanner]: pulls the luminance (Y) plane out of each YUV_420_888 frame
 * and hands it to the (Android-free, unit-tested) decoder. [onQr] fires for every frame that yields a QR;
 * the caller is expected to ignore foreign codes and tear the camera down once it gets a VALID pairing —
 * so a non-Shield QR appearing first can't wedge the scan.
 *
 * QR detection is orientation-invariant (it locates the three finder patterns in any rotation), so we feed
 * the raw sensor-oriented luminance without rotating it.
 */
class QrScanAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val text = runCatching {
            QrScanner.decodeLuminance(extractLuminance(image), image.width, image.height)
        }.getOrNull()
        image.close()
        if (text != null) onQr(text)
    }

    /** Copies the Y plane into a tightly-packed width×height buffer, stripping any row padding. */
    private fun extractLuminance(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.also { it.rewind() }
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h)
        if (rowStride == w) {
            buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        } else {
            val row = ByteArray(rowStride)
            for (r in 0 until h) {
                val len = minOf(rowStride, buffer.remaining())
                if (len <= 0) break
                buffer.get(row, 0, len)
                System.arraycopy(row, 0, out, r * w, minOf(w, len))
            }
        }
        return out
    }
}
