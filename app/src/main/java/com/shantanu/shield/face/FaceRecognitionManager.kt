package com.shantanu.shield.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

@Singleton
class FaceRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var inputSize = 112 
    private var outputSize = 128
    private val threshold = 0.5f

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "facenet.tflite")
            interpreter = Interpreter(model)
            
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            if (inputShape != null && inputShape.size >= 3) {
                inputSize = inputShape[1]
            }

            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            if (outputShape != null && outputShape.isNotEmpty()) {
                outputSize = outputShape[outputShape.size - 1]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun detectFace(bitmap: Bitmap): Bitmap? = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bounds = face.boundingBox
                    val left = bounds.left.coerceAtLeast(0)
                    val top = bounds.top.coerceAtLeast(0)
                    val width = bounds.width().coerceAtMost(bitmap.width - left)
                    val height = bounds.height().coerceAtMost(bitmap.height - top)
                    
                    if (width > 0 && height > 0) {
                        val faceBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
                        continuation.resume(faceBitmap)
                    } else {
                        continuation.resume(null)
                    }
                } else {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener {
                continuation.resume(null)
            }
    }

    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        val interp = interpreter ?: return FloatArray(outputSize)

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        val tensorImage = TensorImage(interp.getInputTensor(0).dataType())
        tensorImage.load(faceBitmap)
        val processedImage = imageProcessor.process(tensorImage)

        val output = Array(1) { FloatArray(outputSize) }
        interp.run(processedImage.buffer, output)
        
        return l2Normalize(output[0])
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sum = 0.0f
        for (v in embedding) sum += v * v
        val l2 = sqrt(sum.toDouble()).toFloat()
        for (i in embedding.indices) embedding[i] /= l2
        return embedding
    }

    fun compareEmbeddings(emb1: FloatArray, emb2: FloatArray): Float {
        var dotProduct = 0.0f
        val size = minOf(emb1.size, emb2.size)
        for (i in 0 until size) {
            dotProduct += emb1[i] * emb2[i]
        }
        return dotProduct
    }

    fun isMatch(emb1: FloatArray, emb2: FloatArray): Boolean {
        return compareEmbeddings(emb1, emb2) > threshold
    }
}
