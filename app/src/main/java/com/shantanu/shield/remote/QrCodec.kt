package com.shantanu.shield.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pure QR encode/decode for the pairing handshake — no Android, no camera, so it is unit-testable on the
 * JVM. The two Android-only adapters live in the UI layer: [BitMatrix] → `Bitmap` for display, and a
 * CameraX `ImageProxy` Y-plane → luminance `ByteArray` for scanning. See PARENT_REMOTE_REPORT_PLAN.md §2.
 */
object QrEncoder {
    /** Encodes [text] into a square QR [BitMatrix] (`true` = dark module). [size] is the pixel side. */
    fun encode(text: String, size: Int = 512): BitMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 4,   // standard 4-module quiet zone — reliable decode (camera + tests)
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    }
}

object QrScanner {
    /**
     * Decodes a QR from a grayscale/luminance buffer — e.g. the Y plane of a CameraX YUV_420 frame. Pure
     * and allocation-light; returns null when no QR is present (the common per-frame case while aiming).
     * Never throws.
     */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): String? = runCatching {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
}
