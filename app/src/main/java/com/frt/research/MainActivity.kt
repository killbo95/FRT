package com.frt.research

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var liveCount: TextView
    private lateinit var sessionStats: TextView
    private lateinit var performanceStats: TextView
    private lateinit var historyText: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var sessionActive = false
    private var paused = false
    private var sessionStartedAt = 0L
    private var faceObservations = 0
    private var framesAnalyzed = 0
    private var peakFaces = 0
    private var currentFaces = 0
    private var currentFps = 0
    private var lastProcessingMs = 0L
    private var perfFrames = 0
    private var perfWindowStart = 0L
    private var savedHistory = JSONArray()
    private var pendingCsv = ""

    private val preferences by lazy { getSharedPreferences("frt_local_data", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginSessionAndCamera()
            else status.text = "Camera permission was not granted."
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(pendingCsv) }
                }
                Toast.makeText(this, "Session history exported.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not export: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedHistory = loadHistory()
        buildInterface()
        refreshHistory()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(android.graphics.Color.rgb(7, 17, 31))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            setPadding(0, 0, 0, 12)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        status = makeText("Camera is off", 14f, android.graphics.Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 4)
        }
        liveCount = makeText("Faces in view: —", 23f, android.graphics.Color.rgb(22, 184, 232)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        performanceStats = makeText("Performance: waiting for camera", 12f, android.graphics.Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        sessionStats = makeText("Session: not started", 14f, android.graphics.Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }

        startButton = makeButton("START SESSION") { requestStart() }
        pauseButton = makeButton("PAUSE") { togglePause() }
        val stop = makeButton("SAVE & STOP") { finishSession("Session saved.") }
        val switch = makeButton("SWITCH CAMERA") { switchCamera() }
        val export = makeButton("EXPORT CSV") { exportHistory() }
        val clear = makeButton("CLEAR HISTORY") {
            AlertDialog.Builder(this)
                .setTitle("Clear saved history?")
                .setMessage("This permanently deletes the session summaries stored on this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    savedHistory = JSONArray()
                    preferences.edit().remove("sessions").apply()
                    refreshHistory()
                    Toast.makeText(this, "History cleared.", Toast.LENGTH_SHORT).show()
                }.show()
        }

        val historyTitle = makeText("SAVED SESSIONS", 16f, android.graphics.Color.rgb(22, 184, 232)).apply {
            setPadding(0, 18, 0, 8)
        }
        historyText = makeText("No saved sessions yet.", 13f, android.graphics.Color.LTGRAY).apply {
            setPadding(0, 0, 0, 14)
        }
        val privacy = makeText(
            "PRIVACY: Detection runs on this device. FRT saves session statistics only—not face photos, face templates, or identities. The camera runs only while this screen is active.",
            12f, android.graphics.Color.LTGRAY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(4, 12, 4, 8)
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(previewView, LinearLayout.LayoutParams(-1, dp(250)).apply { bottomMargin = dp(6) })
        content.addView(status)
        content.addView(liveCount)
        content.addView(sessionStats)
        content.addView(performanceStats)
        content.addView(startButton)
        content.addView(pauseButton)
        content.addView(stop)
        content.addView(switch)
        content.addView(historyTitle)
        content.addView(historyText)
        content.addView(export)
        content.addView(clear)
        content.addView(privacy)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        pauseButton.isEnabled = false
    }

    private fun makeText(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestStart() {
        if (sessionActive) {
            if (paused) togglePause()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            beginSessionAndCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun beginSessionAndCamera() {
        sessionActive = true
        paused = false
        sessionStartedAt = System.currentTimeMillis()
        faceObservations = 0
        framesAnalyzed = 0
        peakFaces = 0
        currentFaces = 0
        currentFps = 0
        lastProcessingMs = 0L
        perfFrames = 0
        perfWindowStart = SystemClock.elapsedRealtime()
        performanceStats.text = "Performance: measuring…"
        liveCount.text = "Faces in view: 0"
        updateSessionStats()
        startButton.text = "SESSION RUNNING"
        startButton.isEnabled = false
        pauseButton.text = "PAUSE"
        pauseButton.isEnabled = true
        startCamera()
    }

    private fun togglePause() {
        if (!sessionActive) return
        paused = !paused
        pauseButton.text = if (paused) "RESUME" else "PAUSE"
        status.text = if (paused) "Detection paused" else "Live detection active"
        if (paused) liveCount.text = "Faces in view: —"
    }

    private fun switchCamera() {
        if (!sessionActive) {
            Toast.makeText(this, "Start a session first.", Toast.LENGTH_SHORT).show()
            return
        }
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun startCamera() {
        status.text = "Starting camera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                detector?.close()
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build()
                val newDetector = FaceDetection.getClient(options)
                detector = newDetector
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (paused || !sessionActive || !analyzing.compareAndSet(false, true)) {
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
                    val processingStartedAt = SystemClock.elapsedRealtime()
                    newDetector.process(image)
                        .addOnSuccessListener { faces ->
                            val found = faces.size
                            synchronized(this) {
                                currentFaces = found
                                faceObservations += found
                                framesAnalyzed += 1
                                if (found > peakFaces) peakFaces = found
                            }
                            runOnUiThread {
                                if (sessionActive && !paused) {
                                    liveCount.text = "Faces in view: $found"
                                    status.text = if (found == 0) "Scanning live feed…" else "Face(s) detected • on-device"
                                    updateSessionStats()
                                }
                            }
                        }
                        .addOnFailureListener {
                            runOnUiThread { status.text = "Detection error. Try restarting the session." }
                        }
                        .addOnCompleteListener {
                            val completedAt = SystemClock.elapsedRealtime()
                            lastProcessingMs = completedAt - processingStartedAt
                            perfFrames += 1
                            if (completedAt - perfWindowStart >= 1000L) {
                                currentFps = (perfFrames * 1000L / (completedAt - perfWindowStart)).toInt()
                                perfFrames = 0
                                perfWindowStart = completedAt
                            }
                            runOnUiThread {
                                if (sessionActive) performanceStats.text = "Analysis: ${currentFps} FPS • Last frame: ${lastProcessingMs} ms"
                            }
                            analyzing.set(false)
                            proxy.close()
                        }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                status.text = if (paused) "Detection paused" else "Live detection active"
            } catch (e: Exception) {
                status.text = "Could not start camera: ${e.localizedMessage ?: "unknown error"}"
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateSessionStats() {
        if (!sessionActive) return
        val seconds = ((System.currentTimeMillis() - sessionStartedAt) / 1000).coerceAtLeast(0)
        sessionStats.text = "Time: ${formatDuration(seconds)}   •   Face observations: $faceObservations   •   Peak in view: $peakFaces"
    }

    private fun finishSession(message: String) {
        if (!sessionActive) {
            cameraProvider?.unbindAll()
            status.text = "Camera stopped"
            return
        }
        cameraProvider?.unbindAll()
        sessionActive = false
        paused = false
        val endedAt = System.currentTimeMillis()
        val duration = ((endedAt - sessionStartedAt) / 1000).coerceAtLeast(0)
        val record = JSONObject().apply {
            put("startedAt", sessionStartedAt)
            put("endedAt", endedAt)
            put("durationSeconds", duration)
            put("faceObservations", faceObservations)
            put("framesAnalyzed", framesAnalyzed)
            put("peakFaces", peakFaces)
        }
        savedHistory.put(record)
        persistHistory()
        detector?.close()
        detector = null
        liveCount.text = "Faces in view: —"
        performanceStats.text = "Performance: session ended"
        status.text = "Camera stopped • session saved"
        sessionStats.text = "Last session: ${formatDuration(duration)} • $faceObservations face observations • peak $peakFaces"
        startButton.text = "START NEW SESSION"
        startButton.isEnabled = true
        pauseButton.text = "PAUSE"
        pauseButton.isEnabled = false
        refreshHistory()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadHistory(): JSONArray {
        return try {
            JSONArray(preferences.getString("sessions", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun persistHistory() {
        preferences.edit().putString("sessions", savedHistory.toString()).apply()
    }

    private fun refreshHistory() {
        if (!::historyText.isInitialized) return
        if (savedHistory.length() == 0) {
            historyText.text = "No saved sessions yet."
            return
        }
        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val lines = mutableListOf<String>()
        for (i in savedHistory.length() - 1 downTo 0) {
            val item = savedHistory.optJSONObject(i) ?: continue
            val date = formatter.format(Date(item.optLong("startedAt")))
            lines.add(
                "${i + 1}. $date\n   Duration: ${formatDuration(item.optLong("durationSeconds"))} • Face observations: ${item.optInt("faceObservations")} • Peak: ${item.optInt("peakFaces")}"
            )
        }
        historyText.text = lines.joinToString("\n\n")
    }

    private fun exportHistory() {
        val csv = StringBuilder("Started,Ended,Duration seconds,Face observations,Frames analyzed,Peak faces\n")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (i in 0 until savedHistory.length()) {
            val item = savedHistory.optJSONObject(i) ?: continue
            csv.append(formatter.format(Date(item.optLong("startedAt")))).append(',')
                .append(formatter.format(Date(item.optLong("endedAt")))).append(',')
                .append(item.optLong("durationSeconds")).append(',')
                .append(item.optInt("faceObservations")).append(',')
                .append(item.optInt("framesAnalyzed")).append(',')
                .append(item.optInt("peakFaces")).append('\n')
        }
        pendingCsv = csv.toString()
        exportLauncher.launch("FRT-session-history.csv")
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
    }

    override fun onStop() {
        if (sessionActive) finishSession("Session auto-saved when FRT was closed.")
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        detector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
