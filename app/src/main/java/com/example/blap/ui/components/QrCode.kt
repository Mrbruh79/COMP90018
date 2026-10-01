package com.example.blap.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal fun createQrBitmap(payload: String, size: Int = 900): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    val dark = android.graphics.Color.BLACK
    val light = android.graphics.Color.WHITE
    for (y in 0 until size) {
        for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) dark else light
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
