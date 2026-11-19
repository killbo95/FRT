package com.frt.research

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Camera permission is required."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 20)
            setBackgroundColor(android.graphics.Color.rgb(7, 17, 31))
        }
        val title = TextView(this).apply {
            text = "FRT"
            textSize = 30f
            setTextColor(android.graphics.Color.rgb(22, 184, 232))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "FACIAL RECOGNITION TECHNOLOGY • RESEARCH"
            textSize = 11f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        status = TextView(this).apply {
            text = "Camera is off"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 14, 8, 6)
        }
        count = TextView(this).apply {
            text = "Faces detected: —"
            textSize = 21f
            setTextColor(android.graphics.Color.rgb(22, 184, 232))
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 12)
        }
        val start = Button(this).apply {
            text = "START LIVE DETECTION"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        val stop = Button(this).apply {
            text = "STOP CAMERA"
            setOnClickListener {
                ProcessCameraProvider.getInstance(this@MainActivity).get().unbindAll()
                status.text = "Camera stopped"
                count.text = "Faces detected: —"
            }
        }
        val privacy = TextView(this).apply {
            text = "Privacy: on-device face detection only. No identity matching, face-image storage, or internet face search."
            textSize = 12f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 18; bottomMargin = 8 })
        root.addView(status)
        root.addView(count)
        root.addView(start)
        root.addView(stop)
        root.addView(privacy)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        status.text = "Starting camera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
            val detector = FaceDetection.getClient(options)
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                if (!analyzing.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    analyzing.set(false)
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        runOnUiThread {
                            count.text = "Faces detected: ${faces.size}"
                            status.text = if (faces.isEmpty()) "Scanning live feed…" else "Face(s) detected • local processing"
                        }
                    }
                    .addOnFailureListener { runOnUiThread { status.text = "Detection error. Restart the camera." } }
                    .addOnCompleteListener {
                        analyzing.set(false)
                        proxy.close()
                    }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                status.text = "Live detection active"
            } catch (e: Exception) {
                status.text = "Could not start camera: ${e.localizedMessage ?: "unknown error"}"
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
