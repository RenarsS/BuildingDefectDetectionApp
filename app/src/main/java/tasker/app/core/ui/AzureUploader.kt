package com.example.buildingdefect

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object AzureUploader {
    var functionUrl: String =
        "https://task-app-backend-hxhzcpgyd4hvctgc.swedencentral-01.azurewebsites.net/api/ResultsFunction"

    private val client = OkHttpClient()

    suspend fun upload(bitmap: Bitmap, detections: List<Detection>) = withContext(Dispatchers.IO) {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val imageBytes = bos.toByteArray()
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val detectionsJson = JSONArray()
        detections.forEach { det ->
            val detObj = JSONObject()
            detObj.put("classIndex", det.classIndex) // Int OK
            detObj.put("confidence", det.confidence.toDouble()) // ✅ Float -> Double

            val bboxArr = JSONArray()
                .put(det.bbox.left.toDouble())
                .put(det.bbox.top.toDouble())
                .put(det.bbox.right.toDouble())
                .put(det.bbox.bottom.toDouble())

            detObj.put("bbox", bboxArr)
            detectionsJson.put(detObj)
        }

        val bodyJson = JSONObject()
            .put("image", imageBase64)
            .put("detections", detectionsJson)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(functionUrl)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Upload failed with status ${response.code}")
            }
        }
    }
}
