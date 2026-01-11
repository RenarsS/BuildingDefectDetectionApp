package com.example.buildingdefect

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.buildingdefect.ui.theme.BuildingDefectTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DetectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BuildingDefectTheme {
                AppScreen()
            }
        }
    }

    @Composable
    private fun AppScreen() {
        val detections by viewModel.detections.collectAsState()
        val error by viewModel.error.collectAsState()

        val pickImage =
            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    val bmp = uriToBitmap(uri)
                    if (bmp != null) viewModel.detect(bmp, applicationContext)
                }
            }

        val takePicture =
            rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
                if (bitmap != null) viewModel.detect(bitmap, applicationContext)
            }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pickImage.launch("image/*") }) {
                    Text("Pick image")
                }
                Button(onClick = { takePicture.launch(null) }) {
                    Text("Take photo")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Detections: ${detections.size}")

            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error: $error")
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
