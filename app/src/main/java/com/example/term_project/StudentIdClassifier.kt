package com.example.term_project

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class StudentIdClassifier(
    private val context: Context
) {
    private val interpreter: Interpreter
    private val labels: List<String>

    private val imageSize = 224
    private val numClasses = 2

    init {
        interpreter = Interpreter(loadModelFile("privacy_classifier.tflite"))
        labels = loadLabels("labels.txt")
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun loadLabels(labelName: String): List<String> {
        val labels = mutableListOf<String>()

        context.assets.open(labelName).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?

                while (true) {
                    line = reader.readLine()
                    if (line == null) break

                    if (line!!.isNotBlank()) {
                        labels.add(line!!.trim())
                    }
                }
            }
        }

        return labels
    }

    fun classify(bitmap: Bitmap): ClassificationResult {
        val inputBuffer = bitmapToByteBuffer(bitmap)

        val output = Array(1) { FloatArray(numClasses) }

        interpreter.run(inputBuffer, output)

        val probabilities = output[0]

        var maxIndex = 0
        var maxConfidence = probabilities[0]

        for (i in probabilities.indices) {
            if (probabilities[i] > maxConfidence) {
                maxConfidence = probabilities[i]
                maxIndex = i
            }
        }

        val label = labels[maxIndex]

        return ClassificationResult(
            label = label,
            confidence = maxConfidence,
            allProbabilities = labels.zip(probabilities.toList()).toMap()
        )
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)

        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(imageSize * imageSize)
        resizedBitmap.getPixels(
            pixels,
            0,
            imageSize,
            0,
            0,
            imageSize,
            imageSize
        )

        var pixelIndex = 0

        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val pixel = pixels[pixelIndex++]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Colab에서 MobileNetV2 preprocess_input을 썼기 때문에
                // Android에서도 [-1, 1] 범위로 맞춰야 함
                byteBuffer.putFloat(r.toFloat())
                byteBuffer.putFloat(g.toFloat())
                byteBuffer.putFloat(b.toFloat())
            }
        }

        return byteBuffer
    }

    fun close() {
        interpreter.close()
    }
}

data class ClassificationResult(
    val label: String,
    val confidence: Float,
    val allProbabilities: Map<String, Float>
)