package com.example.buildingdefect

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DetectionViewModel : ViewModel() {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var detector: YoloV12Detector? = null

    fun detect(bitmap: Bitmap, appContext: Context) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (detector == null) {
                    detector = YoloV12Detector(appContext.applicationContext)
                    detector!!.warmup()
                }

                val results = detector!!.detect(bitmap)
                _detections.value = results

                AzureUploader.upload(bitmap, results)

                _error.value = null
            } catch (t: Throwable) {
                android.util.Log.e("YOLO", "Detect/upload crashed", t)
                _error.value = t.message
            }
        }
    }

}
