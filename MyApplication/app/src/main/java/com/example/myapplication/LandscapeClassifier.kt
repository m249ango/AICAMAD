package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class LandscapeClassifier(context: Context) {

    val labels = listOf(
        "삼등분 법칙", "수직", "수평", "대각선",
        "곡선", "삼각형", "중심", "대칭", "패턴"
    )

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)  // R, G, B (ImageNet)
    private val std  = floatArrayOf(0.229f, 0.224f, 0.225f)  // R, G, B (ImageNet)
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val interpreter: Interpreter = Interpreter(loadModelFile(context))

    fun classify(bitmap: Bitmap): LandscapeResult? {
        return try {
            val resized     = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = preprocessBitmap(resized)
            val output      = Array(1) { FloatArray(labels.size) }
            interpreter.run(inputBuffer, output)
            val logits = output[0]

            // Softmax
            val expValues = logits.map { Math.exp(it.toDouble()) }
            val sumExp    = expValues.sum()
            val probs     = expValues.map { (it / sumExp).toFloat() }

            val topIdx = probs.indices.maxByOrNull { probs[it] } ?: return null
            val score  = (probs[topIdx] * 100).toInt().coerceIn(0, 100)
            LandscapeResult(label = labels[topIdx], score = score)
        } catch (_: Exception) {
            null
        }
    }

    fun close() { interpreter.close() }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        bitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixelBuffer) {
            // ARGB_8888: A=상위8비트, R, G, B (각 8비트)
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8  and 0xFF) / 255f
            val b = (pixel        and 0xFF) / 255f
            buffer.putFloat((r - mean[0]) / std[0])
            buffer.putFloat((g - mean[1]) / std[1])
            buffer.putFloat((b - mean[2]) / std[2])
        }
        return buffer
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(assetFd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }

    companion object {
        private const val MODEL_FILE = "efficientnet_lite0.tflite"
        private const val INPUT_SIZE = 224
    }
}

data class LandscapeResult(val label: String, val score: Int)
