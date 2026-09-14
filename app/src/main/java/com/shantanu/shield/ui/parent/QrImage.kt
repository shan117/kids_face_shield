package com.shantanu.shield.ui.parent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.shantanu.shield.remote.QrEncoder

/**
 * Android-side adapter for the pure [QrEncoder]: renders the QR [com.google.zxing.common.BitMatrix] into a
 * black-on-white [Bitmap] and shows it. The encode logic itself is unit-tested in `QrCodecTest`; this is
 * just the pixel plumbing.
 */
fun qrBitmap(text: String, sizePx: Int = 640): Bitmap {
    val matrix = QrEncoder.encode(text, sizePx)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { qrBitmap(text) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Pairing QR code",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
