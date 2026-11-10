package com.sunny.glassesbridge

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var saved: TextView
    private lateinit var preview: ImageView
    private lateinit var connect: Button
    private lateinit var start: Button
    private lateinit var record30: Button
    private lateinit var record60: Button
    private lateinit var stop: Button
    private lateinit var disconnect: Button
    private lateinit var share: Button
    private lateinit var save: Button
    private lateinit var remove: Button
    private lateinit var sound: CheckBox
    private lateinit var autoSnap: CheckBox
    private lateinit var handsFree: CheckBox
    private lateinit var handsFreeStatus: TextView
    private lateinit var handsFreeDuration: RadioGroup
    private var inputs: Inputs? = null
    private var inputCollectors: Job? = null
    private var inputTimeout: Job? = null
    private var inputAttaching = false
    private var inputActive = false
    private val doublePress = DoublePressGate(650)
    private val duration30Id = View.generateViewId()
    private val duration60Id = View.generateViewId()
    private var initialized = false
    private var initAttempted = false
    private var registered = false
    private var destroyed = false
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var sessionScope: CoroutineScope? = null
    private var streaming = false
    private var recording = false
    private var busy = false
    private var foreground = false
    private var saving = false
    private var preparingRecording = false
    private var autoPreviewPending = false
    private var queuedSeconds: Int? = null
    private var connectJob: Job? = null
    private var failureJob: Job? = null
    private var reconnectAfterUnregister = false
    private var registrationPending = false
    private val connectionLog = ArrayDeque<String>()
    private var timer: Job? = null
    private var connectionTimeout: Job? = null
    private var latest: File? = null
    private var requestedSeconds = 30
    // Only the single recording worker touches recorder or frame dimensions.
    private var recorder: ClipRecorder? = null
    private var width = 0
    private var height = 0
    private var lastPreview = 0L
    @Volatile private var lastFrameMs = 0L

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connectMeta() else message("Allow Nearby devices in Android Settings to connect the glasses.")
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { sound.isChecked = false; message("Microphone permission denied. The clip will be silent.") }
        record(requestedSeconds)
    }
    private val glassesPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        result.onSuccess { permission ->
            if (permission == PermissionStatus.Granted && foreground) startSession()
            else if (permission == PermissionStatus.Granted) { busy = false; autoPreviewPending = true }
            else { busy = false; queuedSeconds = null; message("Camera access was declined in Meta AI. Tap Record to request it again."); controls() }
        }.onFailure { error, _ -> busy = false; queuedSeconds = null; message(error.description); controls() }
    }
    private val importVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importClip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        latest = recordings().listFiles()?.filter { it.name.matches(Regex("glasses-[0-9]+\\.mp4")) }
            ?.maxByOrNull { it.lastModified() }
        showSaved()
        controls()
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            message("Tap Connect Meta AI to allow Nearby devices and link the glasses.")
        } else initialize()
    }

    private fun initialize() {
        if (initialized || initAttempted) return
        initAttempted = true
        try {
            Wearables.initialize(applicationContext).onSuccess {
                initialized = true
                scope.launch {
                    Wearables.registrationState.collect { state ->
                        val wasRegistered = registered
                        registered = state == RegistrationState.REGISTERED
                        trace("Registration: $state; developer mode: ${Wearables.isDevMode}")
                        if (registered && !wasRegistered) autoPreviewPending = true
                        if (reconnectAfterUnregister && state == RegistrationState.AVAILABLE) {
                            reconnectAfterUnregister = false
                            registrationPending = true
                            registerIfReady()
                        }
                        if (!recording && !busy) message(if (registered) "Connected. Choose Record 30 s or Record 60 s." else "Tap Connect Meta AI to link this app.")
                        if (!registered && session != null) endSession("Meta AI connection ended.")
                        controls()
                        maybeAutoPreview()
                    }
                }
                scope.launch {
                    Wearables.registrationErrorStream.collect { error -> message(error.description) }
                }
            }.onFailure { error, _ -> message("Meta setup: ${error.description}") }
        } catch (e: Exception) { message("Meta setup: ${e.message}") }
        if (!initialized) initAttempted = false
        controls()
    }

    private fun connectMeta() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        initialize()
        if (registered) {
            autoPreviewPending = true
            maybeAutoPreview()
            return
        }
        if (initialized) runCatching { Wearables.startRegistration(this) }
            .onFailure { message("Could not open Meta AI: ${it.message}") }
    }

    private fun requestPreview() {
        if (!initialized || !registered || session != null || busy || !foreground) return
        busy = true; controls()
        message("Requesting the glasses camera…")
        scope.launch {
            try {
                Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess {
                    if (it == PermissionStatus.Granted) startSession() else glassesPermission.launch(Permission.CAMERA)
                }.onFailure { error, _ -> busy = false; queuedSeconds = null; message(error.description); controls() }
            } catch (e: Exception) { busy = false; queuedSeconds = null; message(e.message ?: "Camera permission failed."); controls() }
        }
    }

    private fun startSession() {
        if (session != null || !foreground) return
        if (!Wearables.isDevMode) {
            busy = false; queuedSeconds = null
            message("Meta registered this app without Developer Mode. Tap Reset Meta connection, enable Developer Mode in Meta AI, and approve the connection again.")
            trace("Start blocked: SDK developer mode is false")
            controls()
            return
        }
        busy = true; controls()
        message("Waiting for Meta to make the glasses available…")
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val selector = AutoDeviceSelector()
                val eligible = withTimeoutOrNull(20_000) { selector.activeDeviceFlow().first { it != null } }
                if (eligible == null) {
                    busy = false; queuedSeconds = null
                    trace("No eligible glasses after 20 seconds; dev mode: ${Wearables.isDevMode}")
                    message("Meta has not made any glasses available to this app. Keep them worn and connected. Use Reset Meta connection, or Update glasses runtime if Meta AI requests an installation.")
                    controls()
                    return@launch
                }
                if (!foreground || destroyed) { busy = false; controls(); return@launch }
                message("Starting the glasses camera…")
                trace("Eligible glasses found; starting session; dev mode: ${Wearables.isDevMode}")
                val failures = ConnectionFailure()
                Wearables.createSession(selector).onSuccess { created ->
                    session = created
                    val jobs = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
                    sessionScope = jobs
                    jobs.launch {
                        created.errors.collect { error ->
                            failures.add(error.toString(), error.description)
                            trace("Session error: $error — ${error.description}")
                            finishFailedSession(created, failures)
                        }
                    }
                    jobs.launch {
                        created.state.collect { state ->
                            trace("Session state: $state")
                            when (state) {
                                DeviceSessionState.STARTED -> if (camera == null) attachCamera(created, jobs)
                                DeviceSessionState.PAUSED -> { streaming = false; controls(); endSession("Glasses paused. Your clip was stopped.") }
                                DeviceSessionState.STOPPED -> {
                                    failures.add("SESSION_STOPPED", "The SDK reports that the device session stopped.")
                                    finishFailedSession(created, failures)
                                }
                                else -> Unit
                            }
                        }
                    }
                    created.start()
                    connectionTimeout?.cancel()
                    connectionTimeout = scope.launch {
                        delay(20_000)
                        if (!streaming && session === created) endSession("No camera frames received. Check Bluetooth, Meta AI Developer Mode, and glasses firmware.")
                    }
                }.onFailure { error, _ ->
                    failures.add(error.toString(), error.description)
                    trace("Create session failed: $error — ${error.description}")
                    busy = false; queuedSeconds = null; message(failures.recoveryMessage()); controls()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; endSession("Connection failed: ${e.message}") }
        }
    }

    private fun finishFailedSession(created: DeviceSession, failures: ConnectionFailure) {
        if (session !== created || failureJob?.isActive == true) return
        // Meta often emits SESSION_ENDED_BY_DEVICE followed by the actionable error.
        // Keep collecting briefly before stopping the listener and discarding the session.
        failureJob = scope.launch {
            delay(500)
            if (session === created) endSession(failures.recoveryMessage())
        }
    }

    private fun trace(event: String) {
        if (connectionLog.size >= 40) connectionLog.removeFirst()
        connectionLog.addLast("${SystemClock.elapsedRealtime() / 1000}s $event")
    }

    private fun registerIfReady() {
        if (!registrationPending || !foreground || !initialized) return
        if (Wearables.registrationState.value != RegistrationState.AVAILABLE) return
        registrationPending = false
        trace("Requesting fresh registration")
        runCatching { Wearables.startRegistration(this) }
            .onFailure { message("Meta registration could not open: ${it.message}") }
    }

    private fun resetMetaConnection() {
        if (!initialized || recording || saving || preparingRecording) return
        autoPreviewPending = false
        endSession("Resetting this app's Meta connection…")
        trace("User requested connection reset")
        if (Wearables.registrationState.value == RegistrationState.REGISTERED) {
            reconnectAfterUnregister = true
            runCatching { Wearables.startUnregistration(this) }
                .onFailure { reconnectAfterUnregister = false; message("Could not reset Meta connection: ${it.message}") }
        } else {
            registrationPending = true
            registerIfReady()
        }
    }

    private fun copyConnectionDetails() {
        val state = if (initialized) "Registration: ${Wearables.registrationState.value}\nSDK developer mode: ${Wearables.isDevMode}\nVisible glasses: ${Wearables.devices.value.size}" else "SDK not initialized"
        val report = "Glasses Snap Bridge v0.5\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}\n$state\n\nStatus: ${status.text}\n\n${connectionLog.joinToString("\n")}"
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Connection details", report))
        Toast.makeText(this, "Connection details copied. Paste them into this chat.", Toast.LENGTH_LONG).show()
    }

    private suspend fun attachCamera(created: DeviceSession, jobs: CoroutineScope) {
        created.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)).onSuccess { added ->
            camera = added
            jobs.launch {
                added.stream.errorStream.collect { error ->
                    trace("Stream error: $error — ${error.description}")
                    if (error == StreamError.STREAM_ERROR) message("Camera warning: ${error.description}")
                    else endSession("Camera error: $error — ${error.description}")
                }
            }
            jobs.launch {
                added.stream.state.collect { state ->
                    if (state == StreamState.PAUSED || state == StreamState.CLOSED || (state == StreamState.STOPPED && streaming)) {
                        endSession("Camera stopped. Your clip was stopped.")
                    }
                }
            }
            jobs.launch {
                try {
                    added.stream.videoStream.collect { frame ->
                        // Copy immediately: the SDK can reuse this ByteBuffer after delivery.
                        val source = frame.buffer.duplicate()
                        val bytes = ByteArray(source.remaining())
                        source.get(bytes)
                        val w = frame.width
                        val h = frame.height
                        val arrival = SystemClock.elapsedRealtime()
                        require(!frame.isCompressed && bytes.size == w * h * 3 / 2) { "Unexpected camera frame format. This prototype expects decoded I420 frames." }
                        withContext(worker) {
                            width = w; height = h
                            lastFrameMs = arrival
                            recorder?.frame(bytes, w, h, arrival)
                            if (arrival - lastPreview >= 200) {
                                lastPreview = arrival
                                val jpeg = ByteArrayOutputStream()
                                YuvImage(YuvPlanes.toNv21(bytes, w, h), ImageFormat.NV21, w, h, null)
                                    .compressToJpeg(Rect(0, 0, w, h), 70, jpeg)
                                val image = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
                                withContext(Dispatchers.Main.immediate) {
                                    preview.setImageBitmap(image)
                                    if (!streaming && session === created && foreground) {
                                        streaming = true; busy = false
                                        connectionTimeout?.cancel()
                                        message("Camera ready. Choose Record 30 s or Record 60 s.")
                                        controls()
                                        startQueuedRecording()
                                        maybeAttachInputs()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { endSession("Recording interrupted: ${e.message}") }
            }
            added.stream.start().onFailure { error, _ -> endSession(error.description) }
        }.onFailure { error, _ -> endSession(error.description) }
    }

    private fun record(seconds: Int) {
        if (!initialized || !registered || recording || saving || preparingRecording || destroyed) return
        queuedSeconds = seconds
        clock.text = "${seconds} s selected"
        if (!foreground) return
        if (streaming && !busy) {
            startQueuedRecording()
        } else {
            message("${seconds} s selected. Waiting for the glasses camera…")
            if (session == null && !busy) requestPreview()
            controls()
        }
    }

    private fun maybeAttachInputs() {
        val activeSession = session ?: return
        val jobs = sessionScope ?: return
        if (!handsFree.isChecked || !foreground || !streaming || inputs != null || inputAttaching) return
        inputAttaching = true
        handsFreeStatus.text = "Checking glasses button access…"
        jobs.launch {
            try {
                activeSession.addInputs(InputsConfiguration(sources = setOf(InputSource.CAPTURE_BUTTON), consumeBack = false))
                    .onSuccess { capability ->
                        if (!handsFree.isChecked || session !== activeSession) {
                            inputAttaching = false
                            activeSession.removeInputs().onFailure { error, _ -> trace("Remove late inputs: ${error.description}") }
                            return@onSuccess
                        }
                        inputs = capability
                        inputAttaching = false
                        inputCollectors = jobs.launch {
                            launch {
                                capability.state.collect { state ->
                                    inputActive = state == InputsState.ACTIVE
                                    doublePress.reset()
                                    trace("Inputs state: $state")
                                    handsFreeStatus.text = if (inputActive) "Button ready. Double-press the capture button to record ${if (handsFreeDuration.checkedRadioButtonId == duration60Id) 60 else 30} s."
                                        else "Glasses button: $state. Wait for Button ready."
                                    if (inputActive) { inputTimeout?.cancel(); beep(ToneGenerator.TONE_PROP_BEEP) }
                                    controls()
                                }
                            }
                            launch {
                                capability.errors.collect { error ->
                                    if (error != null) {
                                        inputActive = false; doublePress.reset()
                                        trace("Inputs error: $error — ${error.description}")
                                        handsFreeStatus.text = "Glasses button unavailable: ${error.description}. Meta may need to enable Inputs for this app. On-screen recording still works."
                                        controls()
                                    }
                                }
                            }
                            launch {
                                capability.events.collect { event ->
                                    when (event) {
                                        is InputEvent.Capture -> {
                                            trace("Capture button: ${event.pressType}")
                                            if (event.source == InputSource.CAPTURE_BUTTON && event.pressType == CapturePressType.SHORT_PRESS) {
                                                val canStart = handsFree.isChecked && inputActive && foreground && streaming && !recording && !saving && !preparingRecording && !busy
                                                if (canStart && doublePress.shortPress(SystemClock.elapsedRealtime())) {
                                                    autoSnap.isChecked = true
                                                    record(if (handsFreeDuration.checkedRadioButtonId == duration60Id) 60 else 30)
                                                } else if (!canStart) doublePress.reset()
                                            } else doublePress.reset()
                                        }
                                        is InputEvent.Nav, is InputEvent.Select, is InputEvent.Back,
                                        is InputEvent.Button, is InputEvent.Drag -> Unit
                                    }
                                }
                            }
                        }
                        inputTimeout = jobs.launch {
                            delay(12_000)
                            if (!inputActive && inputs === capability) handsFreeStatus.text = "Glasses button did not activate. Meta Inputs enablement may be required. On-screen recording still works."
                        }
                    }.onFailure { error, _ ->
                        inputAttaching = false
                        trace("Inputs unavailable: $error — ${error.description}")
                        handsFreeStatus.text = "Glasses button unavailable: ${error.description}. This experimental feature may need Meta Inputs approval."
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                inputAttaching = false
                handsFreeStatus.text = "Glasses button unavailable: ${e.message}"
            }
        }
    }

    private fun disarmInputs() {
        doublePress.reset(); inputActive = false
        inputTimeout?.cancel(); inputTimeout = null
        inputCollectors?.cancel(); inputCollectors = null
        val attached = inputs
        inputs = null
        inputAttaching = false
        val activeSession = session
        if (attached != null && activeSession != null) scope.launch {
            activeSession.removeInputs().onFailure { error, _ -> trace("Remove inputs: ${error.description}") }
        }
        handsFreeStatus.text = "Glasses button off."
    }

    private fun beep(tone: Int) {
        scope.launch {
            var generator: ToneGenerator? = null
            try {
                generator = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
                generator.startTone(tone, 130)
                delay(180)
            } catch (_: Exception) { /* Audio routing must never prevent recording. */ }
            finally { generator?.release() }
        }
    }

    private fun startQueuedRecording() {
        if (!foreground || !streaming || busy || recording || saving || preparingRecording) return
        val seconds = queuedSeconds ?: return
        queuedSeconds = null
        requestedSeconds = seconds
        if (sound.isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else beginRecording(seconds)
    }

    private fun maybeAutoPreview() {
        if (!autoPreviewPending || !foreground || !registered || busy || session != null || saving) return
        autoPreviewPending = false
        requestPreview()
    }

    private fun beginRecording(seconds: Int) {
        if (!streaming || recording || busy || destroyed || !foreground) return
        preparingRecording = true
        busy = true; controls()
        val withSound = sound.isChecked
        val initialSession = session
        scope.launch {
            try {
                val created = withContext(worker) {
                    check(width > 0 && height > 0 && SystemClock.elapsedRealtime() - lastFrameMs < 3000) { "Wait for a live glasses preview before recording." }
                    ClipRecorder(applicationContext, width, height, withSound).also { recorder = it }
                }
                if (!streaming || session !== initialSession || destroyed) {
                    withContext(worker) { recorder?.discard(); recorder = null }
                    preparingRecording = false; busy = false; controls()
                    return@launch
                }
                preparingRecording = false; recording = true; busy = false
                doublePress.reset()
                if (handsFree.isChecked) beep(ToneGenerator.TONE_PROP_BEEP2)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                message(created.soundWarning ?: if (withSound) "Recording with phone microphone." else "Recording without sound.")
                controls()
                timer = scope.launch {
                    while (isActive && recording) {
                        val elapsed = SystemClock.elapsedRealtime() - created.startedMs
                        clock.text = "${(elapsed / 1000).coerceAtMost(seconds.toLong())} / $seconds s"
                        if (elapsed >= seconds * 1000L) { finishRecording(openSnap = true); break }
                        if (SystemClock.elapsedRealtime() - lastFrameMs > 5000) { endSession("Camera frames stopped. Saved the available clip."); break }
                        delay(200)
                    }
                }
            } catch (e: Exception) { preparingRecording = false; busy = false; message("Could not record: ${e.message}"); controls() }
        }
    }

    private suspend fun finishRecording(openSnap: Boolean = false) {
        if (!recording) return
        recording = false; saving = true; busy = true
        timer?.cancel(); timer = null
        // Do not cancel the caller timer before its finalization runs.
        withContext(NonCancellable) {
            var savedSuccessfully = false
            message("Saving video…"); controls()
            try {
                val result = withContext(worker) {
                    val active = checkNotNull(recorder)
                    recorder = null
                    active.finish() to active.soundWarning
                }
                latest = result.first
                savedSuccessfully = true
                if (handsFree.isChecked) beep(ToneGenerator.TONE_PROP_ACK)
                message(result.second ?: "Video ready. Tap Open in Snapchat.")
                showSaved()
            } catch (e: Exception) { message("Video could not be saved: ${e.message}") }
            saving = false; busy = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controls()
            if (savedSuccessfully && openSnap && foreground && !destroyed && autoSnap.isChecked) shareVideo()
        }
    }

    private fun endSession(reason: String = "Preview ended.") {
        doublePress.reset(); inputActive = false; inputAttaching = false
        inputTimeout?.cancel(); inputTimeout = null
        inputCollectors?.cancel(); inputCollectors = null
        inputs = null
        handsFreeStatus.text = if (handsFree.isChecked) "Glasses button waiting for connection." else "Glasses button off."
        failureJob?.cancel(); failureJob = null
        connectJob?.cancel(); connectJob = null
        queuedSeconds = null
        val oldSession = session
        val oldCamera = camera
        session = null; camera = null
        streaming = false
        connectionTimeout?.cancel(); connectionTimeout = null
        sessionScope?.cancel(); sessionScope = null
        scope.launch {
            if (recording) finishRecording()
            runCatching { oldCamera?.stop() }
            runCatching { oldSession?.stop() }
            busy = false
            message(reason)
            controls()
        }
    }

    private fun shareVideo() {
        val file = latest?.takeIf { it.exists() } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Glasses video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.snapchat.android")
        }
        try { startActivity(intent) }
        catch (e: Exception) {
            message("Snapchat could not accept the video. Save to Gallery, then use Snapchat → Memories → Camera Roll.")
        }
    }

    private fun saveToGallery() {
        val file = latest?.takeIf { it.exists() } ?: return
        busy = true; controls()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GlassesSnapBridge")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
                    try {
                        checkNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                }
                message("Saved to Movies/GlassesSnapBridge. It is available in Snapchat Camera Roll.")
            } catch (e: Exception) { message("Could not save to Gallery: ${e.message}") }
            busy = false; controls()
        }
    }

    private fun importClip(uri: Uri) {
        busy = true; controls()
        scope.launch {
            try {
                latest = withContext(Dispatchers.IO) {
                    val out = File(recordings(), "glasses-${System.currentTimeMillis()}.mp4")
                    try {
                        checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                            out.outputStream().use { output ->
                                val block = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(block)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 250L * 1024 * 1024) { "Choose a video under 250 MB." }
                                    output.write(block, 0, count)
                                }
                            }
                        }
                        val metadata = android.media.MediaMetadataRetriever()
                        try {
                            metadata.setDataSource(out.absolutePath)
                            val duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                            require(duration in 1..60_500) { "Choose a video of 60 seconds or less." }
                        } finally { metadata.release() }
                        out
                    } catch (e: Exception) { out.delete(); throw e }
                }
                showSaved(); message("Imported clip ready. Tap Open in Snapchat.")
            } catch (e: Exception) { message("Could not import: ${e.message}") }
            busy = false; controls()
        }
    }

    private fun recordings() = File(filesDir, "recordings").apply { mkdirs() }
    private fun message(text: String) { if (!destroyed) status.text = text }
    private fun showSaved() {
        if (destroyed) return
        saved.text = latest?.let { "Latest clip • ${"%.1f".format(it.length() / 1048576.0)} MB\n${it.name}" } ?: "Your recorded clips stay on this phone."
    }
    private fun controls() {
        if (destroyed) return
        connect.isEnabled = !busy && !recording && session == null
        start.isEnabled = initialized && registered && session == null && !busy
        // A tap can request the camera; frames are not a prerequisite for tapping.
        record30.isEnabled = initialized && registered && !recording && !saving && !preparingRecording
        record60.isEnabled = record30.isEnabled
        stop.text = if (queuedSeconds != null && !recording) "Cancel recording" else "Stop & save"
        stop.isEnabled = (recording && !busy) || queuedSeconds != null
        disconnect.isEnabled = session != null && !busy
        sound.isEnabled = !recording && !busy
        autoSnap.isEnabled = !recording && !busy
        handsFree.isEnabled = !recording && !saving && !preparingRecording
        handsFreeDuration.isEnabled = !recording && !saving && !preparingRecording
        for (i in 0 until handsFreeDuration.childCount) handsFreeDuration.getChildAt(i).isEnabled = handsFreeDuration.isEnabled
        if (foreground && (recording || (handsFree.isChecked && session != null))) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val canShare = latest?.exists() == true && !recording && !busy
        share.isEnabled = canShare; save.isEnabled = canShare; remove.isEnabled = canShare
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(24))
            setBackgroundColor(Color.rgb(16, 19, 24))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(dp(22), dp(16) + bars.top, dp(22), dp(24) + bars.bottom)
            insets
        }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(6))
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }
        fun row(vararg buttons: View) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) }) }
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.app_logo)
                contentDescription = "Glasses Snap Bridge logo"
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })
            addView(text("GLASSES SNAP BRIDGE", 14f, Color.rgb(255, 220, 0)))
        })
        root.addView(text("Capture your view.", 28f))
        root.addView(text("Record on your glasses. Share the finished clip.", 14f, Color.LTGRAY))
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); contentDescription = "Live preview from your glasses" }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
        status = text("Setting up…", 14f)
        root.addView(status)
        clock = text("Choose 30 or 60 seconds", 22f).apply { gravity = Gravity.CENTER }
        root.addView(clock)
        handsFreeDuration = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply { id = duration30Id; text = "Button: 30 s"; setTextColor(Color.WHITE) })
            addView(RadioButton(this@MainActivity).apply { id = duration60Id; text = "Button: 60 s"; setTextColor(Color.WHITE) })
            check(duration30Id)
            setOnCheckedChangeListener { _, _ ->
                doublePress.reset()
                if (inputActive) handsFreeStatus.text = "Button ready. Double-press to record ${if (checkedRadioButtonId == duration60Id) 60 else 30} s."
            }
        }
        root.addView(handsFreeDuration)
        handsFreeStatus = text("Glasses button off. Requires Meta Inputs access.", 13f, Color.LTGRAY)
        handsFree = CheckBox(this).apply {
            text = "Arm glasses double-press (experimental)"; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, armed ->
                doublePress.reset()
                if (armed) {
                    autoSnap.isChecked = true
                    handsFreeStatus.text = "Connecting the glasses button…"
                    if (streaming) maybeAttachInputs()
                    else if (registered && !busy && session == null) requestPreview()
                    else if (!registered) handsFreeStatus.text = "Connect Meta AI first, then wait for Button ready."
                } else disarmInputs()
                controls()
            }
        }
        root.addView(handsFree)
        root.addView(handsFreeStatus)
        record30 = button("Record 30 s") { record(30) }
        record60 = button("Record 60 s") { record(60) }
        row(record30, record60)
        connect = button("Connect Meta AI", ::connectMeta)
        start = button("Preview glasses", ::requestPreview)
        row(connect, start)
        sound = CheckBox(this).apply {
            text = "Include sound from phone microphone"; isChecked = false; setTextColor(Color.WHITE)
        }
        root.addView(sound)
        autoSnap = CheckBox(this).apply {
            text = "Open Snapchat when recording ends"; isChecked = true; setTextColor(Color.WHITE)
        }
        root.addView(autoSnap)
        stop = button("Stop & save") {
            if (!recording && queuedSeconds != null) {
                queuedSeconds = null; clock.text = "Choose 30 or 60 seconds"
                message("Recording request cancelled."); controls()
            } else scope.launch { finishRecording(openSnap = true) }
        }
        disconnect = button("End preview") { endSession() }
        row(stop, disconnect)
        saved = text("", 12f, Color.LTGRAY); root.addView(saved)
        share = button("Open in Snapchat", ::shareVideo)
        root.addView(share)
        save = button("Save to Gallery", ::saveToGallery)
        remove = button("Delete latest") {
            android.app.AlertDialog.Builder(this).setMessage("Delete this app's latest clip? Gallery copies stay available.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    latest?.delete(); latest = null; showSaved(); controls()
                }.show()
        }
        row(save, remove)
        root.addView(button("Reset Meta connection") { resetMetaConnection() })
        root.addView(button("Update glasses runtime") {
            if (initialized && !recording && !saving) {
                Wearables.openDATGlassesAppUpdate(this).onFailure { error, _ -> message(error.description) }
            }
        })
        root.addView(button("Update glasses firmware") {
            if (initialized && !recording && !saving) {
                Wearables.openFirmwareUpdate(this).onFailure { error, _ -> message(error.description) }
            }
        })
        root.addView(button("Copy connection details") { copyConnectionDetails() })
        root.addView(button("Import an existing MP4 clip") {
            if (!recording && !busy) importVideo.launch(arrayOf("video/mp4"))
        })
        root.addView(text("Keep this app open while recording. Snapchat opens with the saved clip when the option above is on.\nDeveloper prototype v0.5 • Requires Meta AI Developer Mode.", 12f, Color.GRAY))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onResume() {
        super.onResume()
        foreground = true
        if (handsFree.isChecked && registered && session == null) autoPreviewPending = true
        controls()
        registerIfReady()
        maybeAutoPreview()
        if (queuedSeconds != null) {
            if (streaming && !busy) startQueuedRecording()
            else if (registered && !busy && session == null) requestPreview()
        }
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        queuedSeconds = null
        connectJob?.cancel(); connectJob = null
        if (session == null) { busy = false; controls() }
        if (session != null) endSession("Preview ended because the app left the screen. Any recording was stopped and saved.")
    }

    override fun onDestroy() {
        destroyed = true
        inputCollectors?.cancel(); inputTimeout?.cancel()
        connectJob?.cancel(); failureJob?.cancel()
        connectionTimeout?.cancel()
        timer?.cancel()
        sessionScope?.cancel()
        val oldCamera = camera; val oldSession = session
        camera = null; session = null
        scope.launch {
            withContext(worker) { recorder?.discard(); recorder = null }
            runCatching { oldCamera?.stop() }
            runCatching { oldSession?.stop() }
            scope.cancel()
            worker.close()
        }
        super.onDestroy()
    }
}
