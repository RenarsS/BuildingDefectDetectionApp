package com.example.buildingdefect

import android.content.Context
import android.graphics.*
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedInputStream
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max

data class Detection(
    val classIndex: Int,
    val confidence: Float,
    val bbox: RectF
)

class YoloV12Detector(
    private val context: Context,
    private val assetModelName: String = "best.onnx",
    private val inputSize: Int = 512,
    private val numClasses: Int = 5,
    private val outputsAreXYXY: Boolean = true
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy { createSession() }

    fun warmup() { session.inputNames.size }

    private fun createSession(): OrtSession {
        val modelBytes = context.assets.open(assetModelName).use { input ->
            BufferedInputStream(input).readBytes()
        }
        val opts = OrtSession.SessionOptions()
        return env.createSession(modelBytes, opts)
    }

    data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val srcW: Int,
        val srcH: Int
    )

    private fun letterbox(src: Bitmap): LetterboxResult {
        val srcW = src.width
        val srcH = src.height
        val r = min(inputSize / srcW.toFloat(), inputSize / srcH.toFloat())
        val newW = (srcW * r).toInt().coerceAtLeast(1)
        val newH = (srcH * r).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)

        val out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f
        canvas.drawBitmap(resized, padX, padY, null)

        return LetterboxResult(out, r, padX, padY, srcW, srcH)
    }

    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        return try {
            val lb = letterbox(bitmap)
            val inputTensor = bitmapToTensorCHW(lb.bitmap)

            val inputName = session.inputNames.first()
            val outputs = session.run(Collections.singletonMap(inputName, inputTensor))
            inputTensor.close()

            outputs.use { result ->
                val v: Any? = result[0].value
                if (v == null) return@use emptyList()

                val (boxesInput, scores, classIds) = decodeFloat_1_C_N(v, confThreshold)

                val dets = ArrayList<Detection>(boxesInput.size)
                for (i in boxesInput.indices) {
                    val b = boxesInput[i]

                    val x1 = ((b.left - lb.padX) / lb.scale)
                    val y1 = ((b.top - lb.padY) / lb.scale)
                    val x2 = ((b.right - lb.padX) / lb.scale)
                    val y2 = ((b.bottom - lb.padY) / lb.scale)

                    val left = x1.coerceIn(0f, (lb.srcW - 1).toFloat())
                    val top = y1.coerceIn(0f, (lb.srcH - 1).toFloat())
                    val right = x2.coerceIn(0f, (lb.srcW - 1).toFloat())
                    val bottom = y2.coerceIn(0f, (lb.srcH - 1).toFloat())

                    if (right <= left || bottom <= top) continue

                    dets.add(
                        Detection(
                            classIndex = classIds[i],
                            confidence = scores[i],
                            bbox = RectF(left, top, right, bottom)
                        )
                    )
                }

                nms(dets, iouThreshold)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "detect() crashed", t)
            emptyList()
        }
    }

    private fun bitmapToTensorCHW(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val chw = FloatArray(3 * h * w)
        var p = 0
        val hw = h * w
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = pixels[p++]
                val r = ((v shr 16) and 0xFF) / 255f
                val g = ((v shr 8) and 0xFF) / 255f
                val b = (v and 0xFF) / 255f

                val idx = y * w + x
                chw[idx] = r
                chw[idx + hw] = g
                chw[idx + 2 * hw] = b
            }
        }

        val fb = FloatBuffer.wrap(chw)
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        return OnnxTensor.createTensor(env, fb, shape)
    }

    private fun decodeFloat_1_C_N(
        outputValue: Any,
        confThreshold: Float
    ): Triple<List<RectF>, List<Float>, List<Int>> {

        val boxes = ArrayList<RectF>()
        val scores = ArrayList<Float>()
        val classIds = ArrayList<Int>()

        val a0 = outputValue as? Array<*> ?: run {
            Log.e(TAG, "Unsupported output type: ${outputValue.javaClass.name}")
            return Triple(emptyList(), emptyList(), emptyList())
        }

        val b0 = a0.getOrNull(0) as? Array<*> ?: run {
            Log.e(TAG, "Output not rank-3 float[1][C][N]")
            return Triple(emptyList(), emptyList(), emptyList())
        }

        if (b0.isEmpty() || b0[0] !is FloatArray) {
            Log.e(TAG, "Output inner type unexpected: ${b0.getOrNull(0)?.javaClass?.name}")
            return Triple(emptyList(), emptyList(), emptyList())
        }

        @Suppress("UNCHECKED_CAST")
        val m = b0 as Array<FloatArray>

        val C = m.size
        val N = m[0].size
        val neededC = 5 + numClasses
        if (C < neededC) {
            Log.e(TAG, "C too small: C=$C expected >= $neededC (numClasses=$numClasses)")
            return Triple(emptyList(), emptyList(), emptyList())
        }

        var maxScore = 0f
        for (i in 0 until N) {
            val b0v = m[0][i]
            val b1v = m[1][i]
            val b2v = m[2][i]
            val b3v = m[3][i]

            val obj = sigmoid(m[4][i])

            var bestClass = 0
            var bestProb = 0f
            for (c in 0 until numClasses) {
                val p = sigmoid(m[5 + c][i])
                if (p > bestProb) {
                    bestProb = p
                    bestClass = c
                }
            }

            val score = obj * bestProb
            if (score > maxScore) maxScore = score
            if (score < confThreshold) continue

            val rect = if (outputsAreXYXY) {
                val left = b0v
                val top = b1v
                val right = b2v
                val bottom = b3v
                RectF(left, top, right, bottom)
            } else {
                val cx = b0v
                val cy = b1v
                val w = b2v
                val h = b3v
                RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            }

            val clamped = RectF(
                rect.left.coerceIn(0f, inputSize.toFloat()),
                rect.top.coerceIn(0f, inputSize.toFloat()),
                rect.right.coerceIn(0f, inputSize.toFloat()),
                rect.bottom.coerceIn(0f, inputSize.toFloat())
            )
            if (clamped.right <= clamped.left || clamped.bottom <= clamped.top) continue

            boxes.add(clamped)
            scores.add(score)
            classIds.add(bestClass)
        }

        Log.d(TAG, "decode: C=$C N=$N maxScore=$maxScore kept=${boxes.size}")
        return Triple(boxes, scores, classIds)
    }

    private fun nms(dets: List<Detection>, iouThreshold: Float): List<Detection> {
        if (dets.isEmpty()) return dets

        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)

            val it = sorted.iterator()
            while (it.hasNext()) {
                val d = it.next()
                if (best.classIndex != d.classIndex) continue
                if (iou(best.bbox, d.bbox) >= iouThreshold) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH

        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - interArea

        return if (union <= 0f) 0f else interArea / union
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private companion object {
        private const val TAG = "YOLO"
    }
}
