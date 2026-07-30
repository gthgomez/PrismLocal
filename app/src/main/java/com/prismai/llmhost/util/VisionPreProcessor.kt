package com.prismai.llmhost.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-processes image attachments before native vision model projection:
 * 1. Efficiently downsamples high-res images using `inSampleSize`.
 * 2. Reads EXIF metadata to apply correct rotation.
 * 3. Letterboxes images into square tiles (default 512x512) to preserve aspect ratio & OCR quality.
 * 4. Recycles intermediate Bitmaps to prevent JVM heap OOM spikes.
 */
object VisionPreProcessor {

    private const val TAG = "VisionPreProcessor"
    private const val DEFAULT_TARGET_DIMENSION = 512

    /**
     * Loads, rotates, letterboxes, and resizes an image from [uri] into a bitmap of [targetDimension] x [targetDimension].
     */
    fun processImage(context: Context, uri: Uri, targetDimension: Int = DEFAULT_TARGET_DIMENSION): Bitmap? {
        return try {
            // 1. Calculate inSampleSize
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val originalWidth = boundsOptions.outWidth
            val originalHeight = boundsOptions.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return null

            var inSampleSize = 1
            var halfWidth = originalWidth / 2
            var halfHeight = originalHeight / 2
            while (halfWidth / inSampleSize >= targetDimension && halfHeight / inSampleSize >= targetDimension) {
                inSampleSize *= 2
            }

            // 2. Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // 3. EXIF orientation correction
            val rotationDegrees = getExifRotationDegrees(context, uri)
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                rotated
            } else {
                decodedBitmap
            }

            // 4. Letterbox padding to square targetDimension x targetDimension
            val outputBitmap = Bitmap.createBitmap(targetDimension, targetDimension, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            canvas.drawColor(Color.BLACK) // Neutral padding

            val width = rotatedBitmap.width
            val height = rotatedBitmap.height
            val scale = targetDimension.toFloat() / maxOf(width, height)
            val scaledWidth = (width * scale).toInt()
            val scaledHeight = (height * scale).toInt()

            val left = (targetDimension - scaledWidth) / 2f
            val top = (targetDimension - scaledHeight) / 2f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, scaledWidth, scaledHeight, true)
            canvas.drawBitmap(scaledBitmap, left, top, paint)

            if (scaledBitmap != rotatedBitmap) {
                scaledBitmap.recycle()
            }
            rotatedBitmap.recycle()

            outputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-process vision image attachment", e)
            null
        }
    }

    /**
     * Converts a pre-processed bitmap into a DirectByteBuffer for native NDK consumption.
     */
    fun bitmapToRgbaDirectBuffer(bitmap: Bitmap): ByteBuffer {
        val bytesPerPixel = 4
        val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * bytesPerPixel)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF).toByte()
            val g = (pixel shr 8 and 0xFF).toByte()
            val b = (pixel and 0xFF).toByte()
            val a = (pixel shr 24 and 0xFF).toByte()
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
            buffer.put(a)
        }
        buffer.rewind()
        return buffer
    }

    private fun getExifRotationDegrees(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exifInterface = ExifInterface(stream)
                when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
