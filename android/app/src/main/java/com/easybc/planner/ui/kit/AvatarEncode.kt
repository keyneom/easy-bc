package com.easybc.planner.ui.kit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Max encoded avatar size after WebP compression (12 KB). */
const val AVATAR_MAX_BYTES = 12 * 1024

private const val AVATAR_SIZE = 128
private const val INITIAL_QUALITY = 70
private const val MIN_QUALITY = 35
private const val QUALITY_STEP = 8

/**
 * Center-crop → 128×128 → WebP (~0.7 quality), re-encoding down until ≤12 KB.
 * Returns base64 WebP without a data-URL prefix.
 */
fun encodeAvatarFromBytes(imageBytes: ByteArray): String {
    val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: error("Could not read that image.")
    return encodeAvatarFromBitmap(decoded)
}

/**
 * Center-crop → 128×128 → WebP (~0.7 quality), re-encoding down until ≤12 KB.
 * Returns base64 WebP without a data-URL prefix.
 */
fun encodeAvatarFromBitmap(source: Bitmap): String {
    val square = centerCropToSquare(source)
    val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true)
    if (square !== source && square !== scaled) square.recycle()
    var quality = INITIAL_QUALITY
    var bytes = compressWebp(scaled, quality)
    while (bytes.size > AVATAR_MAX_BYTES && quality > MIN_QUALITY) {
        quality = (quality - QUALITY_STEP).coerceAtLeast(MIN_QUALITY)
        bytes = compressWebp(scaled, quality)
    }
    if (scaled !== source) scaled.recycle()
    check(bytes.size <= AVATAR_MAX_BYTES) {
        "That photo is still too large after compression. Try a simpler image."
    }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** Decode a base64 WebP (no data-URL prefix) into a Bitmap for Compose. */
fun decodeAvatarBase64(base64: String): Bitmap? {
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private fun centerCropToSquare(source: Bitmap): Bitmap {
    val side = minOf(source.width, source.height)
    val x = (source.width - side) / 2
    val y = (source.height - side) / 2
    return Bitmap.createBitmap(source, x, y, side, side)
}

private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray {
    val stream = ByteArrayOutputStream()
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    check(bitmap.compress(format, quality, stream)) { "WebP encoding failed." }
    return stream.toByteArray()
}
