package com.gslabs.webrtcscreensharing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gslabs.webrtcscreensharing.ui.theme.WebRTCScreenSharingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

private const val TAG = "WebRtcScreenShare"
private const val TARGET_CAPTURE_WIDTH = 1280
private const val TARGET_CAPTURE_HEIGHT = 720
private const val TARGET_CAPTURE_FPS = 30
private const val TARGET_VIDEO_MAX_BITRATE_BPS = 4_000_000
private const val TARGET_VIDEO_MIN_BITRATE_BPS = 1_600_000
private const val ENABLE_MIC_AUDIO = false
private const val USE_SOFTWARE_VIDEO_ENCODER = false

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebRTCScreenSharingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScreenShareApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,       // WS connecting
    WAITING_ACCESS,   // WS open, request-access sent, waiting for grant/deny
    CONNECTED,        // access-granted received
    STREAMING
}

private data class UiLog(
    val level: String,
    val message: String
)

@Composable
private fun ScreenShareApp(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<UiLog>() }
    var state by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var wsUrl by remember { mutableStateOf("ws://192.168.0.137:8554") }

    val client = remember {
        WebRtcScreenShareClient(
            appContext = context.applicationContext,
            onLog = { level, message ->
                logs.add(UiLog(level, message))
            },
            onConnected = {
                state = ConnectionState.CONNECTED
            },
            onWaitingAccess = {
                state = ConnectionState.WAITING_ACCESS
            },
            onAccessDenied = {
                state = ConnectionState.DISCONNECTED
            },
            onDisconnected = {
                state = ConnectionState.DISCONNECTED
            },
            onStreamingChanged = { active ->
                state = if (active) ConnectionState.STREAMING else ConnectionState.CONNECTED
            }
        )
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            coroutineScope.launch {
                client.startScreenShare(result.data!!)
            }
        } else {
            logs.add(UiLog("WARN", "Пользователь отменил разрешение на захват экрана"))
        }
    }

    DisposableEffect(Unit) {
        onDispose { client.release() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Android WebRTC Screen Share",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(text = "Состояние: ${state.name}")

        OutlinedTextField(
            value = wsUrl,
            onValueChange = { wsUrl = it },
            label = { Text("WebSocket URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = state == ConnectionState.DISCONNECTED
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    state = ConnectionState.CONNECTING
                    client.connect(wsUrl)
                },
                enabled = state == ConnectionState.DISCONNECTED
            ) {
                Text("Connect")
            }

            Button(
                onClick = {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                enabled = state == ConnectionState.CONNECTED
            ) {
                Text("Start Screen")
            }

            Button(
                onClick = { client.stopStreaming() },
                enabled = state == ConnectionState.STREAMING
            ) {
                Text("Stop")
            }
        }

        Text("Логи")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(logs) { entry ->
                Text(text = "[${entry.level}] ${entry.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) {
            logs.add(UiLog("INFO", "Приложение инициализировано"))
        }
    }
}

private class WebRtcScreenShareClient(
    private val appContext: Context,
    private val onLog: (String, String) -> Unit,
    private val onConnected: () -> Unit,
    private val onWaitingAccess: () -> Unit,
    private val onAccessDenied: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStreamingChanged: (Boolean) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val okHttpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var hasH264SenderCodec: Boolean = false

    @Volatile
    private var accessState: AccessState = AccessState.IDLE

    private enum class AccessState { IDLE, PENDING, GRANTED }

    init {
        initializePeerConnectionFactory()
    }

    private fun resolveDeviceName(): String {
        // 1. User-visible Bluetooth / device name (Android 8+)
        val bluetoothName = runCatching {
            Settings.Global.getString(appContext.contentResolver, "bluetooth_name")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (bluetoothName != null) return bluetoothName

        // 2. device_name setting (some OEMs)
        val settingName = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (settingName != null) return settingName

        // 3. Fallback: manufacturer + model
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }

    fun connect(url: String) {
        if (webSocket != null) {
            log("WARN", "WebSocket уже открыт")
            return
        }

        accessState = AccessState.IDLE
        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("INFO", "WebSocket подключен — отправляем request-access")
                accessState = AccessState.PENDING
                val deviceName = resolveDeviceName()
                sendMessage(
                    JSONObject()
                        .put("type", "request-access")
                        .put("deviceName", deviceName)
                        .toString()
                )
                dispatchMain(onWaitingAccess)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleSignalingPayload(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("WARN", "WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("WARN", "WebSocket закрыт: $code / $reason")
                cleanupConnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("ERROR", "Ошибка WebSocket: ${t.message}")
                cleanupConnection()
            }
        })
    }

    suspend fun startScreenShare(permissionData: Intent) {
        if (accessState != AccessState.GRANTED) {
            log("WARN", "Нельзя начать трансляцию: доступ ещё не получен (state=$accessState)")
            return
        }

        withContext(Dispatchers.IO) {
            val pc = peerConnection ?: run {
                log("ERROR", "PeerConnection не готов")
                return@withContext
            }

            if (screenCapturer != null) {
                log("WARN", "Трансляция уже запущена")
                return@withContext
            }

            val capturer = ScreenCapturerAndroid(
                permissionData,
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        log("WARN", "MediaProjection остановлен системой")
                        stopStreaming()
                    }
                }
            )

            ProjectionForegroundService.start(appContext)

            val source = peerConnectionFactory!!.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                eglBase!!.eglBaseContext
            )
            capturer.initialize(helper, appContext, source.capturerObserver)
            try {
                capturer.startCapture(TARGET_CAPTURE_WIDTH, TARGET_CAPTURE_HEIGHT, TARGET_CAPTURE_FPS)
            } catch (e: SecurityException) {
                log("ERROR", "MediaProjection requires foreground service type MEDIA_PROJECTION: ${e.message}")
                ProjectionForegroundService.stop(appContext)
                return@withContext
            } catch (e: Throwable) {
                log("ERROR", "Failed to start screen capture: ${e.message}")
                ProjectionForegroundService.stop(appContext)
                return@withContext
            }

            val localVideoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", source)
            localVideoTrack.setEnabled(true)
            pc.addTrack(localVideoTrack)
            configureVideoSenderBitrate(pc)

            var localAudioSource: AudioSource? = null
            var localAudioTrack: AudioTrack? = null
            if (ENABLE_MIC_AUDIO) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                localAudioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
                localAudioTrack = peerConnectionFactory!!.createAudioTrack("mic_track", localAudioSource)
                pc.addTrack(localAudioTrack)
            } else {
                log("WARN", "Microphone audio is disabled to improve stream stability on problematic devices")
            }

            screenCapturer = capturer
            videoSource = source
            videoTrack = localVideoTrack
            audioSource = localAudioSource
            audioTrack = localAudioTrack
            surfaceTextureHelper = helper

            createAndSendOffer(pc)
            dispatchMain { onStreamingChanged(true) }
            log("INFO", "Трансляция экрана запущена")
        }
    }

    private fun configureVideoSenderBitrate(pc: PeerConnection) {
        val videoSender = pc.senders.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }
            ?: run {
                log("WARN", "Video sender not found for bitrate configuration")
                return
            }

        val parameters = videoSender.parameters
        val encodings = parameters.encodings
        if (encodings.isNullOrEmpty()) {
            log("WARN", "Video sender has no encodings for bitrate configuration")
            return
        }

        encodings.forEachIndexed { index, encoding ->
            encoding.maxBitrateBps = TARGET_VIDEO_MAX_BITRATE_BPS
            encoding.minBitrateBps = TARGET_VIDEO_MIN_BITRATE_BPS
            encoding.maxFramerate  = TARGET_CAPTURE_FPS
            log("INFO", "Configured encoding[$index]: min=${encoding.minBitrateBps}, max=${encoding.maxBitrateBps}, fps=${encoding.maxFramerate}")
        }

        val success = videoSender.setParameters(parameters)
        log("INFO", "Video sender bitrate parameters applied=$success")
    }

    fun stopStreaming() {
        executor.execute {
            runCatching { screenCapturer?.stopCapture() }.onFailure { log("WARN", "stopCapture: ${it.message}") }
            runCatching { screenCapturer?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { surfaceTextureHelper?.dispose() }

            screenCapturer    = null
            videoTrack        = null
            videoSource       = null
            audioTrack        = null
            audioSource       = null
            surfaceTextureHelper = null

            runCatching {
                peerConnection?.senders?.forEach { peerConnection?.removeTrack(it) }
            }

            ProjectionForegroundService.stop(appContext)
            dispatchMain { onStreamingChanged(false) }
            log("INFO", "Трансляция остановлена")
        }
    }

    fun release() {
        stopStreaming()
        cleanupConnection()
        executor.execute {
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            runCatching { peerConnectionFactory?.dispose() }
            runCatching { eglBase?.release() }
            peerConnection        = null
            peerConnectionFactory = null
            eglBase               = null
            executor.shutdown()
        }
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val encoderFactory = if (USE_SOFTWARE_VIDEO_ENCODER) {
            SoftwareVideoEncoderFactory().also {
                log("WARN", "Using SOFTWARE video encoder factory (H264 may be unavailable in sender capabilities)")
            }
        } else {
            DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true).also {
                log("INFO", "Using default hardware-capable video encoder factory")
            }
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        logCodecSupport()
    }

    private fun logCodecSupport() {
        val factory = peerConnectionFactory ?: return

        val videoCodecs = runCatching {
            factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                .codecs.mapNotNull { it.name }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        }.getOrElse { emptyList() }

        val audioCodecs = runCatching {
            factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                .codecs.mapNotNull { it.name }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        }.getOrElse { emptyList() }

        val hasH264 = videoCodecs.any { it.equals("H264", ignoreCase = true) }
        val hasOpus = audioCodecs.any { it.equals("opus", ignoreCase = true) }
        hasH264SenderCodec = hasH264

        log("INFO", "Sender video codecs: ${videoCodecs.ifEmpty { listOf("<none>") }.joinToString()} ; H264=$hasH264")
        log("INFO", "Sender audio codecs: ${audioCodecs.ifEmpty { listOf("<none>") }.joinToString()} ; OPUS=$hasOpus")
        if (!hasH264) log("WARN", "H264 отсутствует в RTP sender capabilities — будет использован доступный кодек")
    }

    private fun createPeerConnectionIfNeeded() {
        if (peerConnection != null) return

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf()
        )

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState)        { log("INFO", "Signaling: $s") }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState){ log("INFO", "ICE: $s") }
                override fun onIceConnectionReceivingChange(r: Boolean)                 = Unit
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState)  { log("INFO", "Gathering: $s") }
                override fun onIceCandidate(c: IceCandidate)                            { sendIceCandidate(c) }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>)         = Unit
                override fun onAddStream(s: org.webrtc.MediaStream)                     = Unit
                override fun onRemoveStream(s: org.webrtc.MediaStream)                  = Unit
                override fun onDataChannel(d: org.webrtc.DataChannel)                   = Unit
                override fun onRenegotiationNeeded()                                    { log("INFO", "Renegotiation needed") }
                override fun onAddTrack(r: RtpReceiver, s: Array<out org.webrtc.MediaStream>) = Unit
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState)  {
                    log("INFO", "Connection: $s")
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) {
                        // Принудительно сгенерировать новый keyframe
                        // через временное изменение bitrate (вызывает keyframe в большинстве энкодеров)
                        executor.execute { requestKeyframe() }
                    }
                }

                private fun requestKeyframe() {
                    val sender = peerConnection?.senders
                        ?.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }
                        ?: return
                    val params = sender.parameters
                    if (params.encodings.isNullOrEmpty()) return
                    // Временно роняем битрейт до минимума — энкодер сгенерирует keyframe
                    val originalMax = params.encodings[0].maxBitrateBps
                    params.encodings[0].maxBitrateBps = 100_000
                    sender.setParameters(params)
                    // Через 100мс восстанавливаем
                    mainHandler.postDelayed({
                        executor.execute {
                            val p2 = sender.parameters
                            if (!p2.encodings.isNullOrEmpty()) {
                                p2.encodings[0].maxBitrateBps = originalMax
                                sender.setParameters(p2)
                            }
                        }
                    }, 100)
                }
            }
        )
    }

    private fun createAndSendOffer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", if (ENABLE_MIC_AUDIO) "true" else "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        pc.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                val preferredSdp = if (hasH264SenderCodec)
                    sdp.description.preferCodec("H264", "video")
                else
                    sdp.description
                val local = SessionDescription(SessionDescription.Type.OFFER, preferredSdp)
                pc.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        sendMessage(JSONObject().put("type", "offer").put("sdp", local.description).toString())
                        log("INFO", "Offer отправлен (video=${if (hasH264SenderCodec) "H264 preferred" else "fallback"}, audio=${if (ENABLE_MIC_AUDIO) "OPUS" else "OFF"})")
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?)    { log("ERROR", "setLocalDescription: $e") }
                }, local)
            }
            override fun onSetSuccess()                  = Unit
            override fun onCreateFailure(e: String?)     { log("ERROR", "createOffer: $e") }
            override fun onSetFailure(e: String?)        = Unit
        }, constraints)
    }

    private fun String.preferCodec(codec: String, mediaType: String): String {
        val lines = split("\r\n").toMutableList()
        val mediaLineIndex = lines.indexOfFirst { it.startsWith("m=$mediaType ") }
        if (mediaLineIndex == -1) return this

        val codecRegex = Regex("^a=rtpmap:(\\d+)\\s+${Regex.escape(codec)}/", RegexOption.IGNORE_CASE)
        val preferred = lines.mapNotNull { codecRegex.find(it)?.groupValues?.get(1) }
        if (preferred.isEmpty()) return this

        val parts = lines[mediaLineIndex].split(" ").toMutableList()
        if (parts.size <= 3) return this

        val current = parts.subList(3, parts.size).toList()
        val reordered = preferred.filter { it in current } + current.filter { it !in preferred }
        lines[mediaLineIndex] = (parts.take(3) + reordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun handleSignalingPayload(payload: String) {
        executor.execute {
            val trimmed = payload.trim()
            if (trimmed.isEmpty()) return@execute

            if (trimmed.equals("ping", ignoreCase = true) || trimmed.equals("pong", ignoreCase = true)) {
                log("INFO", "Keepalive: $trimmed")
                return@execute
            }

            val chunks = if (trimmed.contains("\n"))
                trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            else
                listOf(trimmed)

            var parsedAny = false
            for (chunk in chunks) {
                val message = runCatching {
                    asJsonObject(JSONTokener(chunk).nextValue())
                }.getOrNull()

                if (message == null) {
                    log("WARN", "Невалидный JSON: ${chunk.take(300)}")
                    continue
                }
                parsedAny = true
                processSignalingMessage(message)
            }

            if (!parsedAny) log("WARN", "Невалидный signaling payload: ${trimmed.take(200)}")
        }
    }

    private fun asJsonObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is JSONArray  -> arrayPairsToObject(value)
        else          -> null
    }

    private fun arrayPairsToObject(array: JSONArray): JSONObject? {
        val result = JSONObject()
        for (i in 0 until array.length()) {
            val pair = array.opt(i)
            if (pair !is JSONArray || pair.length() < 2) return null
            val key = pair.optString(0)
            if (key.isBlank()) return null
            val raw = pair.opt(1)
            result.put(key, if (raw is JSONArray) asJsonObject(raw) ?: raw else raw)
        }
        return result
    }

    private fun processSignalingMessage(message: JSONObject) {
        when (val type = message.optString("type")) {

            "access-granted" -> {
                log("INFO", "Доступ разрешён сервером (access-granted)")
                accessState = AccessState.GRANTED
                createPeerConnectionIfNeeded()
                dispatchMain(onConnected)
            }

            "access-denied" -> {
                log("WARN", "Доступ отклонён сервером (access-denied)")
                accessState = AccessState.IDLE
                dispatchMain(onAccessDenied)
                cleanupConnection()
            }

            "answer" -> {
                if (accessState != AccessState.GRANTED) {
                    log("WARN", "Получен answer до access-granted — игнорируем")
                    return
                }
                val sdp = message.optString("sdp")
                if (sdp.isBlank()) { log("WARN", "Пустой SDP answer"); return }
                peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        log("INFO", "Remote answer установлен")
                        flushPendingCandidates()
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?)    { log("ERROR", "setRemoteDescription: $e") }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            "ice-candidate" -> {
                if (accessState != AccessState.GRANTED) {
                    log("WARN", "Получен ICE до access-granted — игнорируем")
                    return
                }
                val cj = message.optJSONObject("candidate") ?: run { log("WARN", "Пустой ICE candidate"); return }
                val candidate = IceCandidate(
                    cj.optString("sdpMid"),
                    cj.optInt("sdpMLineIndex", 0),
                    cj.optString("candidate")
                )
                val pc = peerConnection
                if (pc?.remoteDescription == null) {
                    pendingRemoteCandidates.add(candidate)
                    log("INFO", "ICE поставлен в очередь")
                } else {
                    log("INFO", "ICE применён: ${pc.addIceCandidate(candidate)}")
                }
            }

            else -> log("WARN", "Неизвестный тип: $type")
        }
    }

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        pendingRemoteCandidates.forEach { log("INFO", "Queued candidate: ${pc.addIceCandidate(it)}") }
        pendingRemoteCandidates.clear()
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        sendMessage(
            JSONObject()
                .put("type", "ice-candidate")
                .put("candidate", JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex))
                .toString()
        )
    }

    private fun sendMessage(message: String) {
        webSocket?.send(message) ?: log("ERROR", "WebSocket не подключен")
    }

    private fun cleanupConnection() {
        executor.execute {
            runCatching { webSocket?.close(1000, "client cleanup") }
            webSocket = null
            accessState = AccessState.IDLE
            pendingRemoteCandidates.clear()
            stopStreaming()
            dispatchMain(onDisconnected)
        }
    }

    private fun log(level: String, message: String) {
        Log.d(TAG, "[$level] $message")
        dispatchMain { onLog(level, message) }
    }

    private fun dispatchMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}