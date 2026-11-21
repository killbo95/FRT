package com.frt.research

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
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
import java.util.UUID
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var faceOverlay: FaceOverlayView
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
    private var uniqueFaceCount = 0
    private val seenTrackingIds = mutableSetOf<Int>()
    private val stableTrackFrames = mutableMapOf<Int, Int>()
    // QR check-in is attendance only; it is never linked to a camera track.
    private val checkedInParticipants = linkedMapOf<String, String>()
    private var framesAnalyzed = 0
    private var peakFaces = 0
    private var currentFaces = 0
    private var currentFps = 0
    private var lastProcessingMs = 0L
    private var perfFrames = 0
    private var perfWindowStart = 0L
    private var savedHistory = JSONArray()
    private var savedParticipants = JSONArray()
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
        savedParticipants = loadParticipants()
        buildInterface()
        refreshHistory()
    }

    private fun buildInterface() {
        val bg = android.graphics.Color.rgb(6, 12, 24)
        val surface = android.graphics.Color.rgb(15, 27, 45)
        val muted = android.graphics.Color.rgb(145, 163, 186)
        val accent = android.graphics.Color.rgb(39, 220, 183)
        val white = android.graphics.Color.WHITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(17), dp(20), dp(13)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        brand.addView(makeText("FRT", 27f, white).apply { typeface = android.graphics.Typeface.create("sans-serif-black", 0); letterSpacing = 0.08f })
        brand.addView(makeText("FIELD RESEARCH TECHNOLOGY", 9f, muted).apply { letterSpacing = 0.15f })
        header.addView(brand)
        header.addView(TextView(this).apply { text = "● ON-DEVICE"; textSize = 9f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(accent); setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(android.graphics.Color.rgb(14, 54, 55), 18) })
        root.addView(header)

        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(7), dp(8), dp(7)); background = rounded(surface, 17) }
        val pages = listOf("HOME", "MONITOR", "PEOPLE", "RECORDS")
        val keys = listOf("home", "monitor", "people", "records")
        var render: (String) -> Unit = {}
        val navItems = pages.mapIndexed { i, label ->
            TextView(this).apply {
                text = listOf("⌂", "◉", "♙", "▤")[i] + "\n" + label
                gravity = Gravity.CENTER
                textSize = 9f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(muted)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener { render(keys[i]) }
            }
        }
        navItems.forEach { nav.addView(it) }

        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        faceOverlay = FaceOverlayView(this)
        status = makeText("Camera is off • ready when you are", 11f, muted)
        liveCount = makeText("0", 34f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        sessionStats = makeText("No active research session", 11f, muted)
        performanceStats = makeText("Waiting for camera analysis", 10f, muted)
        historyText = makeText("No saved sessions yet.", 11f, muted)
        startButton = makeButton("START SESSION  →") { requestStart() }.apply { backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(12, 151, 130)) }
        pauseButton = makeButton("PAUSE") { togglePause() }.apply { isEnabled = false }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(surface, 17)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) }
        }
        fun heading(box: LinearLayout, title: String, sub: String = "") {
            box.addView(makeText(title, 11f, white).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.07f })
            if (sub.isNotEmpty()) box.addView(makeText(sub, 10f, muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        }
        fun pair(one: Button, two: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(one, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
            addView(two, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        }
        render = { page ->
            host.removeAllViews()
            // Detach reusable widgets from the previous page before placing them in the new page.
            listOf(previewView, faceOverlay, status, liveCount, sessionStats, performanceStats, startButton, pauseButton, historyText)
                .forEach { view -> (view.parent as? android.view.ViewGroup)?.removeView(view) }
            val scroll = ScrollView(this)
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(18)) }
            when (page) {
                "home" -> {
                    body.addView(makeText("RESEARCH OVERVIEW", 10f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.14f })
                    body.addView(makeText("Your vision lab.", 27f, white).apply { setPadding(0, dp(4), 0, dp(3)) })
                    body.addView(makeText("On-device computer vision • private by design", 11f, muted).apply { setPadding(0, 0, 0, dp(15)) })
                    val live = card()
                    heading(live, "LIVE DETECTION", "Camera analysis and temporary tracking IDs")
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(liveCount)
                    row.addView(makeText("FACES IN VIEW", 10f, muted).apply { setPadding(dp(12), 0, 0, 0) })
                    live.addView(row)
                    live.addView(sessionStats)
                    live.addView(performanceStats.apply { setPadding(0, dp(7), 0, dp(4)) })
                    live.addView(startButton, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(12) })
                    body.addView(live)
                    val stats = card()
                    heading(stats, "YOUR RESEARCH DATA")
                    val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    listOf(savedHistory.length().toString() to "SESSIONS", savedParticipants.length().toString() to "PROFILES", checkedInParticipants.size.toString() to "CHECK-INS").forEach { (v, l) ->
                        val m = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(10), dp(5), dp(9)); background = rounded(android.graphics.Color.rgb(20, 36, 57), 12); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) } }
                        m.addView(makeText(v, 21f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                        m.addView(makeText(l, 9f, muted))
                        metrics.addView(m)
                    }
                    stats.addView(metrics); body.addView(stats)
                    val quick = card(); heading(quick, "QUICK ACTIONS", "Your tools, one tap away")
                    quick.addView(pair(makeButton("OPEN MONITOR") { render("monitor") }, makeButton("ADD PERSON") { addParticipantDialog() }))
                    quick.addView(pair(makeButton("SCAN QR") { scanParticipantQr() }, makeButton("VIEW RECORDS") { render("records") }).apply { setPadding(0, dp(8), 0, 0) })
                    body.addView(quick)
                    val privacy = card(); heading(privacy, "PRIVACY STATUS")
                    privacy.addView(makeText("✓ Face analysis stays on this device", 11f, white))
                    privacy.addView(makeText("✓ No face photos or biometric templates stored", 11f, white).apply { setPadding(0, dp(7), 0, 0) })
                    privacy.addView(makeText("✓ QR attendance is separate from camera tracking", 11f, white).apply { setPadding(0, dp(7), 0, 0) })
                    body.addView(privacy)
                }
                "monitor" -> {
                    body.addView(makeText("CAMERA WORKSPACE", 10f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.14f })
                    body.addView(makeText("Live monitor", 26f, white).apply { setPadding(0, dp(4), 0, dp(3)) })
                    body.addView(makeText("Face detection • anonymous temporary tracks", 11f, muted).apply { setPadding(0, 0, 0, dp(13)) })
                    val cam = card()
                    val frame = FrameLayout(this).apply { background = rounded(android.graphics.Color.BLACK, 13); clipToOutline = true }
                    frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
                    frame.addView(faceOverlay, FrameLayout.LayoutParams(-1, -1))
                    cam.addView(frame, LinearLayout.LayoutParams(-1, dp(310)))
                    cam.addView(status.apply { setPadding(0, dp(10), 0, dp(2)) })
                    body.addView(cam)
                    val telemetry = card(); heading(telemetry, "SESSION TELEMETRY")
                    telemetry.addView(liveCount); telemetry.addView(sessionStats); telemetry.addView(performanceStats)
                    body.addView(telemetry)
                    body.addView(pair(pauseButton, makeButton("SWITCH CAMERA") { switchCamera() }))
                    body.addView(makeButton("SAVE & END SESSION") { finishSession("Session saved.") }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(8) })
                }
                "people" -> {
                    body.addView(makeText("PARTICIPANT DIRECTORY", 10f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    body.addView(makeText("People & check-in", 26f, white).apply { setPadding(0, dp(4), 0, dp(3)) })
                    body.addView(makeText("Manage participant profiles and QR attendance", 11f, muted).apply { setPadding(0, 0, 0, dp(13)) })
                    val people = card(); heading(people, "PARTICIPANT PROFILES", savedParticipants.length().toString() + " locally stored profiles")
                    people.addView(makeButton("＋  ADD PARTICIPANT") { addParticipantDialog() })
                    people.addView(makeButton("VIEW PROFILES & QR BADGES") { showParticipants() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
                    body.addView(people)
                    val check = card(); heading(check, "SESSION CHECK-IN", "QR records are not linked to face tracks")
                    check.addView(makeButton("SCAN PARTICIPANT QR") { scanParticipantQr() })
                    check.addView(makeButton("VIEW THIS SESSION'S CHECK-INS") { showCheckIns() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
                    body.addView(check)
                }
                "records" -> {
                    body.addView(makeText("RESEARCH ARCHIVE", 10f, accent).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    body.addView(makeText("Session records", 26f, white).apply { setPadding(0, dp(4), 0, dp(3)) })
                    body.addView(makeText("Review previous runs and export research data", 11f, muted).apply { setPadding(0, 0, 0, dp(13)) })
                    val archive = card(); heading(archive, "SAVED SESSIONS", savedHistory.length().toString() + " records on this device")
                    archive.addView(historyText); body.addView(archive)
                    body.addView(makeButton("EXPORT CSV  ↓") { exportHistory() })
                    body.addView(makeButton("CLEAR LOCAL HISTORY") {
                        AlertDialog.Builder(this).setTitle("Clear saved history?").setMessage("This permanently deletes session summaries stored on this phone.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                                savedHistory = JSONArray(); preferences.edit().remove("sessions").apply(); refreshHistory(); render("records")
                            }.show()
                    }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
                    val details = card(); heading(details, "RECORDED METRICS")
                    details.addView(makeText("Elapsed time, frame count, face observations, temporary tracking IDs, peak faces in view and QR check-in totals.", 11f, muted))
                    body.addView(details)
                }
            }
            scroll.addView(body)
            host.addView(scroll, LinearLayout.LayoutParams(-1, -1))
            navItems.forEachIndexed { i, item ->
                val selected = keys[i] == page
                item.setTextColor(if (selected) accent else muted)
                item.background = if (selected) rounded(android.graphics.Color.rgb(18, 54, 61), 12) else null
            }
        }
        root.addView(host)
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(5), dp(12), dp(8)) })
        setContentView(root)
        render("home")
    }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), android.graphics.Color.rgb(35, 52, 73))
        }

    private fun makeText(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(android.graphics.Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(29, 49, 70))
        minHeight = dp(46)
        setPadding(dp(8), 0, dp(8), 0)
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
        uniqueFaceCount = 0
        seenTrackingIds.clear()
        stableTrackFrames.clear()
        checkedInParticipants.clear()
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
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.08f)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .enableTracking()
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
                            // Reject very small boxes and require a track to persist across frames.
                            val candidates = faces.filter { face ->
                                face.trackingId != null &&
                                    face.boundingBox.width() >= proxy.width * 0.07f &&
                                    face.boundingBox.height() >= proxy.height * 0.07f
                            }
                            val currentIds = candidates.mapNotNull { it.trackingId }.toSet()
                            synchronized(this) {
                                stableTrackFrames.keys.retainAll(currentIds)
                                currentIds.forEach { id -> stableTrackFrames[id] = (stableTrackFrames[id] ?: 0) + 1 }
                            }
                            val stableFaces = candidates.filter { face -> (face.trackingId?.let { stableTrackFrames[it] } ?: 0) >= 2 }
                            val found = stableFaces.size
                            val trackedFaces = stableFaces.map { face -> Pair(Rect(face.boundingBox), face.trackingId ?: -1) }
                            runOnUiThread { faceOverlay.updateFaces(trackedFaces, proxy.width, proxy.height, proxy.imageInfo.rotationDegrees) }
                            synchronized(this) {
                                currentFaces = found
                                faceObservations += found
                                trackedFaces.map { it.second }.filter { it > 0 }.forEach { seenTrackingIds.add(it) }
                                uniqueFaceCount = seenTrackingIds.size
                                framesAnalyzed += 1
                                if (found > peakFaces) peakFaces = found
                            }
                            runOnUiThread {
                                if (sessionActive && !paused) {
                                    liveCount.text = "Faces in view: $found  •  Unique this session: $uniqueFaceCount"
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
        sessionStats.text = "${formatDuration(seconds)} elapsed   •   $uniqueFaceCount tracked faces   •   ${checkedInParticipants.size} QR check-ins   •   Peak in view: $peakFaces"
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
            put("uniqueFaces", uniqueFaceCount)
            put("checkedInCount", checkedInParticipants.size)
            put("checkedInParticipantIds", JSONArray(checkedInParticipants.keys.toList()))
            put("framesAnalyzed", framesAnalyzed)
            put("peakFaces", peakFaces)
        }
        savedHistory.put(record)
        persistHistory()
        detector?.close()
        detector = null
        liveCount.text = "Faces in view: —"
        faceOverlay.updateFaces(emptyList(), 1, 1, 0)
        performanceStats.text = "Performance: session ended"
        status.text = "Camera stopped • session saved"
        sessionStats.text = "Last session: ${formatDuration(duration)} • $uniqueFaceCount unique tracked faces • peak $peakFaces"
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

    private fun loadParticipants(): JSONArray {
        return try { JSONArray(preferences.getString("participants", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    private fun persistParticipants() {
        preferences.edit().putString("participants", savedParticipants.toString()).apply()
    }

    private fun addParticipantDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val nameInput = EditText(this).apply { hint = "Participant name" }
        val ageInput = EditText(this).apply { hint = "Age"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val noteInput = EditText(this).apply { hint = "Optional notes" }
        panel.addView(nameInput); panel.addView(ageInput); panel.addView(noteInput)
        AlertDialog.Builder(this).setTitle("Add consenting participant")
            .setMessage("Only add someone who has agreed to have a profile stored on this phone.")
            .setView(panel).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val age = ageInput.text.toString().toIntOrNull()
                if (name.isBlank() || age == null || age !in 1..120) {
                    Toast.makeText(this, "Enter a name and valid age.", Toast.LENGTH_LONG).show()
                } else {
                    val profile = JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("name", name)
                        put("age", age)
                        put("notes", noteInput.text.toString().trim())
                        put("createdAt", System.currentTimeMillis())
                    }
                    savedParticipants.put(profile)
                    persistParticipants()
                    Toast.makeText(this, "Participant profile saved.", Toast.LENGTH_SHORT).show()
                    showParticipants()
                }
            }.show()
    }

    private fun showParticipants() {
        if (savedParticipants.length() == 0) {
            AlertDialog.Builder(this).setTitle("Participants").setMessage("No participant profiles yet. Add a consenting participant to create a QR badge.")
                .setPositiveButton("OK", null).show()
            return
        }
        val names = Array(savedParticipants.length()) { i ->
            val p = savedParticipants.optJSONObject(i) ?: JSONObject()
            "${p.optString("name")} • age ${p.optInt("age")}"
        }
        AlertDialog.Builder(this).setTitle("Saved participants")
            .setItems(names) { _, which -> showParticipantBadge(which) }
            .setNegativeButton("Close", null).show()
    }

    private fun showParticipantBadge(index: Int) {
        val profile = savedParticipants.optJSONObject(index) ?: return
        val payload = "FRT-PARTICIPANT:${profile.optString("id")}"
        try {
            val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640)
            val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            for (x in 0 until 640) for (y in 0 until 640) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            val image = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; setPadding(dp(24), dp(12), dp(24), dp(12)) }
            val details = TextView(this).apply {
                text = "${profile.optString("name")}\nAge: ${profile.optInt("age")}\nParticipant QR badge"
                gravity = Gravity.CENTER; textSize = 17f; setTextColor(android.graphics.Color.WHITE)
            }
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); addView(details); addView(image, LinearLayout.LayoutParams(-1, dp(260))) }
            AlertDialog.Builder(this).setTitle("Participant badge").setView(layout).setPositiveButton("Done", null).show()
        } catch (_: WriterException) {
            Toast.makeText(this, "Could not create QR badge.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanParticipantQr() {
        if (!sessionActive) {
            Toast.makeText(this, "Start a session before checking participants in.", Toast.LENGTH_SHORT).show()
            return
        }
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue.orEmpty()
                val prefix = "FRT-PARTICIPANT:"
                if (!raw.startsWith(prefix)) {
                    Toast.makeText(this, "That QR code is not an FRT participant badge.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val participantId = raw.removePrefix(prefix)
                val participant = (0 until savedParticipants.length()).asSequence()
                    .mapNotNull { savedParticipants.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == participantId }
                if (participant == null) {
                    Toast.makeText(this, "Participant profile not found on this phone.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val participantName = participant.optString("name", "Participant")
                checkedInParticipants[participantId] = participantName
                updateSessionStats()
                Toast.makeText(this, "$participantName checked in. This is separate from face tracking.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(this, "QR scan failed: ${error.localizedMessage ?: "try again"}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showCheckIns() {
        val names = checkedInParticipants.values.toList()
        val message = if (names.isEmpty()) "No participants checked in during this session." else
            names.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("QR check-ins (${names.size})")
            .setMessage(message).setPositiveButton("Close", null).show()
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
                "${i + 1}. $date\n   Duration: ${formatDuration(item.optLong("durationSeconds"))} • Tracked: ${item.optInt("uniqueFaces", 0)} • QR check-ins: ${item.optInt("checkedInCount", 0)} • Peak: ${item.optInt("peakFaces")}"
            )
        }
        historyText.text = lines.joinToString("\n\n")
    }

    private fun exportHistory() {
        val csv = StringBuilder("Started,Ended,Duration seconds,Unique tracked faces,QR check-ins,Frame detections,Frames analyzed,Peak faces\n")
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (i in 0 until savedHistory.length()) {
            val item = savedHistory.optJSONObject(i) ?: continue
            csv.append(formatter.format(Date(item.optLong("startedAt")))).append(',')
                .append(formatter.format(Date(item.optLong("endedAt")))).append(',')
                .append(item.optLong("durationSeconds")).append(',')
                .append(item.optInt("uniqueFaces", item.optInt("faceObservations"))).append(',')
                .append(item.optInt("checkedInCount", 0)).append(',')
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

    private class FaceOverlayView(context: android.content.Context) : View(context) {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0, 220, 170); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = 32f; style = Paint.Style.FILL
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(0, 120, 100); style = Paint.Style.FILL }
        private var boxes: List<Pair<Rect, Int>> = emptyList()
        private var sourceWidth = 1
        private var sourceHeight = 1
        private var rotation = 0

        fun updateFaces(newBoxes: List<Pair<Rect, Int>>, width: Int, height: Int, degrees: Int) {
            boxes = newBoxes; sourceWidth = width.coerceAtLeast(1); sourceHeight = height.coerceAtLeast(1); rotation = degrees
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rotated = rotation == 90 || rotation == 270
            val imageW = if (rotated) sourceHeight.toFloat() else sourceWidth.toFloat()
            val imageH = if (rotated) sourceWidth.toFloat() else sourceHeight.toFloat()
            val scale = maxOf(width / imageW, height / imageH)
            val offsetX = (width - imageW * scale) / 2f
            val offsetY = (height - imageH * scale) / 2f
            boxes.forEach { (r, trackId) ->
                val mapped = if (rotation == 90) RectF(sourceHeight - r.bottom.toFloat(), r.left.toFloat(), sourceHeight - r.top.toFloat(), r.right.toFloat())
                    else if (rotation == 270) RectF(r.top.toFloat(), sourceWidth - r.right.toFloat(), r.bottom.toFloat(), sourceWidth - r.left.toFloat())
                    else RectF(r)
                mapped.left = mapped.left * scale + offsetX; mapped.right = mapped.right * scale + offsetX
                mapped.top = mapped.top * scale + offsetY; mapped.bottom = mapped.bottom * scale + offsetY
                canvas.drawRoundRect(mapped, 10f, 10f, boxPaint)
                val label = if (trackId > 0) "Track $trackId" else "Face"
                val labelWidth = labelPaint.measureText(label) + 20f
                val labelTop = (mapped.top - 42f).coerceAtLeast(0f)
                canvas.drawRoundRect(mapped.left, labelTop, mapped.left + labelWidth, labelTop + 38f, 7f, 7f, labelBg)
                canvas.drawText(label, mapped.left + 10f, labelTop + 28f, labelPaint)
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        detector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
