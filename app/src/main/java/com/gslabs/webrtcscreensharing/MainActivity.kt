package com.gslabs.webrtcscreensharing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gslabs.webrtcscreensharing.ui.theme.WebRTCScreenSharingTheme
import kotlinx.coroutines.launch
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
import org.webrtc.DataChannel
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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "WebRtcScreenShare"
private const val TARGET_CAPTURE_WIDTH = 1920
private const val TARGET_CAPTURE_HEIGHT = 1080
private const val TARGET_CAPTURE_FPS = 60
private const val TARGET_VIDEO_MAX_BITRATE_BPS = 30_000_000
private const val TARGET_VIDEO_MIN_BITRATE_BPS = 4_000_000
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

// ─────────────────────────────────────────────────────────────────────────────
// UI Layer
// ─────────────────────────────────────────────────────────────────────────────

private enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    WAITING_ACCESS,
    CONNECTED,
    STREAMING
}

private data class UiLog(
    val id: Long = System.nanoTime(),
    val level: String,
    val message: String
)

@Composable
private fun ScreenShareApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<UiLog>() }
    val listState = rememberLazyListState()

    var state by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var wsUrl by remember { mutableStateOf("ws://192.168.200.1:8554") }
    var enableMic by remember { mutableStateOf(false) }
    var enableSystemAudio by remember { mutableStateOf(true) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    // --- Клиент ---
    val client = remember {
        WebRtcScreenShareClient(
            appContext = context.applicationContext,
            onLog = { level, message ->
                logs.add(UiLog(level = level, message = message))
            },
            onConnected = { state = ConnectionState.CONNECTED },
            onWaitingAccess = { state = ConnectionState.WAITING_ACCESS },
            onAccessDenied = { state = ConnectionState.DISCONNECTED },
            onDisconnected = { state = ConnectionState.DISCONNECTED },
            onStreamingChanged = { active ->
                state = if (active) ConnectionState.STREAMING else ConnectionState.CONNECTED
            }
        )
    }

    // --- Запрос разрешения RECORD_AUDIO ---
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            logs.add(UiLog(level = "WARN", message = "Разрешение RECORD_AUDIO отклонено"))
        }
    }

    // --- Запрос MediaProjection ---
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            coroutineScope.launch {
                client.startScreenShare(
                    resultCode = result.resultCode,
                    permissionData = result.data!!,
                    enableMicAudio = enableMic && hasMicPermission,
                    enableSystemAudio = enableSystemAudio
                )
            }
        } else {
            logs.add(UiLog(level = "WARN", message = "Пользователь отменил разрешение на захват экрана"))
        }
    }

    DisposableEffect(Unit) {
        onDispose { client.release() }
    }

    // Авто-скролл логов
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // --- UI ---
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

        // --- Чекбоксы аудио ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = enableMic,
                onCheckedChange = { enableMic = it },
                enabled = state != ConnectionState.STREAMING
            )
            Text("Микрофон")

            Checkbox(
                checked = enableSystemAudio,
                onCheckedChange = { enableSystemAudio = it },
                enabled = state != ConnectionState.STREAMING
            )
            Text("Системное аудио")
        }

        if (!hasMicPermission && enableMic) {
            Button(onClick = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Text("Разрешить микрофон")
            }
        }

        // --- Кнопки управления ---
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
                    // Если нужен микрофон и нет разрешения — запрашиваем
                    if (enableMic && !hasMicPermission) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@Button
                    }
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

            Button(
                onClick = { client.disconnect() },
                enabled = state != ConnectionState.DISCONNECTED
            ) {
                Text("Disconnect")
            }
        }

        Text("Логи")
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(logs, key = { it.id }) { entry ->
                Text(text = "[${entry.level}] ${entry.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        logs.add(UiLog(level = "INFO", message = "Приложение инициализировано"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WebRTC Client
// ─────────────────────────────────────────────────────────────────────────────

private class WebRtcScreenShareClient(
    private val appContext: Context,
    private val onLog: (String, String) -> Unit,
    private val onConnected: () -> Unit,
    private val onWaitingAccess: () -> Unit,
    private val onAccessDenied: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStreamingChanged: (Boolean) -> Unit
) {
    // Все операции с WebRTC-объектами выполняются строго через этот executor
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebRtcExecutor").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // keepalive
        .build()

    // --- WebRTC объекты (доступ только из executor) ---
    @Volatile private var webSocket: WebSocket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // --- Системное аудио ---
    private var systemAudioCapturer: SystemAudioCapturer? = null
    private var audioDataChannel: DataChannel? = null

    // --- MediaProjection (для системного аудио) ---
    private var mediaProjection: MediaProjection? = null

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var hasH264SenderCodec: Boolean = false

    @Volatile
    private var accessState: AccessState = AccessState.IDLE

    @Volatile
    private var released: Boolean = false

    private enum class AccessState { IDLE, PENDING, GRANTED }

    init {
        initializePeerConnectionFactory()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Публичный API
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveDeviceName(): String {
        val bluetoothName = runCatching {
            Settings.Global.getString(appContext.contentResolver, "bluetooth_name")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (bluetoothName != null) return bluetoothName

        val settingName = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (settingName != null) return settingName

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }

    fun connect(url: String) {
        if (released) return
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

    /**
     * Запуск трансляции экрана с возможностью захвата системного аудио.
     *
     * @param resultCode     — код результата из MediaProjection intent
     * @param permissionData — Intent с данными разрешения MediaProjection
     * @param enableMicAudio — включить захват микрофона через WebRTC
     * @param enableSystemAudio — включить захват системного аудио через AudioPlaybackCapture
     */
    fun startScreenShare(
        resultCode: Int,
        permissionData: Intent,
        enableMicAudio: Boolean,
        enableSystemAudio: Boolean
    ) {
        if (released) return
        executeOnWebRtcThread {
            if (accessState != AccessState.GRANTED) {
                log("WARN", "Нельзя начать трансляцию: доступ ещё не получен (state=$accessState)")
                return@executeOnWebRtcThread
            }

            val pc = peerConnection ?: run {
                log("ERROR", "PeerConnection не готов")
                return@executeOnWebRtcThread
            }

            if (screenCapturer != null) {
                log("WARN", "Трансляция уже запущена")
                return@executeOnWebRtcThread
            }

            // Запускаем foreground service ДО создания MediaProjection
            ProjectionForegroundService.start(appContext)

            // --- Видео ---
            val capturer = ScreenCapturerAndroid(
                permissionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        log("WARN", "MediaProjection остановлен системой")
                        stopStreaming()
                    }
                }
            )

            val source = peerConnectionFactory!!.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                eglBase!!.eglBaseContext
            )
            capturer.initialize(helper, appContext, source.capturerObserver)
            try {
                capturer.startCapture(TARGET_CAPTURE_WIDTH, TARGET_CAPTURE_HEIGHT, TARGET_CAPTURE_FPS)
            } catch (e: SecurityException) {
                log("ERROR", "MediaProjection requires foreground service: ${e.message}")
                ProjectionForegroundService.stop(appContext)
                return@executeOnWebRtcThread
            } catch (e: Throwable) {
                log("ERROR", "Ошибка запуска захвата экрана: ${e.message}")
                ProjectionForegroundService.stop(appContext)
                return@executeOnWebRtcThread
            }

            // --- Извлекаем MediaProjection для системного аудио ---
            // ВАЖНО: вызываем ПОСЛЕ startCapture(), чтобы foreground service гарантированно
            // был поднят. ScreenCapturerAndroid внутри вызывает getMediaProjection() и
            // createVirtualDisplay(), что невозможно без запущенного foreground service.
            // К этому моменту сервис точно работает.
            //
            // На Android 10 (API 29) getMediaProjection() можно вызывать многократно
            // с одним и тем же Intent. На Android 14+ (API 34) — только один раз,
            // и потребуется кастомный VideoCapturer вместо ScreenCapturerAndroid.
            if (enableSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projManager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                mediaProjection = try {
                    projManager.getMediaProjection(resultCode, permissionData.clone() as Intent)
                } catch (e: Exception) {
                    log("WARN", "Не удалось получить MediaProjection для аудио: ${e.message}")
                    null
                }
            }

            val localVideoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", source)
            localVideoTrack.setEnabled(true)
            pc.addTrack(localVideoTrack)
            configureVideoSenderBitrate(pc)

            // --- Микрофон (через стандартный WebRTC audio pipeline) ---
            var localAudioSource: AudioSource? = null
            var localAudioTrack: AudioTrack? = null
            if (enableMicAudio) {
                try {
                    val audioConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    }
                    localAudioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
                    localAudioTrack = peerConnectionFactory!!.createAudioTrack("mic_track", localAudioSource)
                    localAudioTrack.setEnabled(true)
                    pc.addTrack(localAudioTrack)
                    log("INFO", "Микрофон добавлен в WebRTC аудио-трек")
                } catch (e: Exception) {
                    log("ERROR", "Ошибка создания аудио-трека микрофона: ${e.message}")
                    localAudioSource?.dispose()
                    localAudioSource = null
                    localAudioTrack = null
                }
            } else {
                log("INFO", "Микрофон отключен пользователем")
            }

            // --- Системное аудио через DataChannel ---
            if (enableSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupSystemAudioDataChannel(pc)
            } else if (enableSystemAudio) {
                log("WARN", "Системное аудио недоступно: требуется Android 10+ (API 29)")
            }

            screenCapturer = capturer
            videoSource = source
            videoTrack = localVideoTrack
            audioSource = localAudioSource
            audioTrack = localAudioTrack
            surfaceTextureHelper = helper

            createAndSendOffer(pc)
            dispatchMain { onStreamingChanged(true) }

            log("INFO", "Трансляция экрана запущена (video=true, mic=$enableMicAudio, systemAudio=${enableSystemAudio && mediaProjection != null})")
        }
    }

    /**
     * Создаёт DataChannel для системного аудио и запускает AudioPlaybackCapture.
     *
     * Протокол DataChannel "system-audio":
     * - Бинарные сообщения: raw PCM 16-bit LE, моно, 48kHz
     * - Каждое сообщение = 1 фрейм (20мс = 960 сэмплов = 1920 байт)
     * - STB должен декодировать и воспроизводить эти фреймы
     *
     * TODO (продакшен): кодировать в Opus перед отправкой для экономии трафика.
     * Raw PCM 48kHz mono 16-bit = ~768 kbps. Opus @ 64kbps = ~64 kbps.
     */
    private fun setupSystemAudioDataChannel(pc: PeerConnection) {
        val projection = mediaProjection ?: run {
            log("WARN", "MediaProjection недоступна для системного аудио")
            return
        }

        val dcInit = DataChannel.Init().apply {
            ordered = true
            // maxRetransmits = 0 // для low-latency; раскомментируйте при необходимости
        }
        val dc = pc.createDataChannel("system-audio", dcInit)
        if (dc == null) {
            log("ERROR", "Не удалось создать DataChannel для системного аудио")
            return
        }
        audioDataChannel = dc

        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val dcState = dc.state()
                log("INFO", "DataChannel system-audio: $dcState")
                if (dcState == DataChannel.State.OPEN) {
                    startSystemAudioCapture(projection, dc)
                } else if (dcState == DataChannel.State.CLOSED) {
                    stopSystemAudioCapture()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // Не ожидаем входящих сообщений на этом канале
            }
        })

        log("INFO", "DataChannel system-audio создан, ожидаем открытия")
    }

    private fun startSystemAudioCapture(projection: MediaProjection, dc: DataChannel) {
        if (systemAudioCapturer != null) return

        val capturer = SystemAudioCapturer(
            context = appContext,
            mediaProjection = projection,
            enableMic = false, // микрофон уже идёт через WebRTC audio track
            onFrame = { pcmData, sizeBytes ->
                if (dc.state() == DataChannel.State.OPEN) {
                    val buffer = ByteBuffer.wrap(pcmData, 0, sizeBytes)
                    dc.send(DataChannel.Buffer(buffer, true)) // binary
                }
            },
            onError = { msg -> log("ERROR", "SystemAudio: $msg") }
        )

        if (capturer.start()) {
            systemAudioCapturer = capturer
            log("INFO", "Захват системного аудио запущен → DataChannel")
        } else {
            log("ERROR", "Не удалось запустить захват системного аудио")
        }
    }

    private fun stopSystemAudioCapture() {
        systemAudioCapturer?.stop()
        systemAudioCapturer = null
    }

    fun stopStreaming() {
        executeOnWebRtcThread {
            // Системное аудио
            stopSystemAudioCapture()
            audioDataChannel?.close()
            audioDataChannel = null

            // MediaProjection для аудио
            mediaProjection?.stop()
            mediaProjection = null

            // Видео
            runCatching { screenCapturer?.stopCapture() }
                .onFailure { log("WARN", "stopCapture: ${it.message}") }
            runCatching { screenCapturer?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }

            // Микрофон
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }

            // Texture helper
            runCatching { surfaceTextureHelper?.dispose() }

            screenCapturer = null
            videoTrack = null
            videoSource = null
            audioTrack = null
            audioSource = null
            surfaceTextureHelper = null

            // Убираем треки из PeerConnection
            runCatching {
                peerConnection?.senders?.forEach { peerConnection?.removeTrack(it) }
            }

            ProjectionForegroundService.stop(appContext)
            dispatchMain { onStreamingChanged(false) }
            log("INFO", "Трансляция остановлена")
        }
    }

    fun disconnect() {
        cleanupConnection()
    }

    fun release() {
        if (released) return
        released = true

        stopStreaming()
        cleanupConnection()

        // Даём время на завершение cleanup, потом останавливаем WebRTC
        executor.execute {
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            runCatching { peerConnectionFactory?.dispose() }
            runCatching { eglBase?.release() }
            peerConnection = null
            peerConnectionFactory = null
            eglBase = null
        }

        // Останавливаем executor снаружи, а не изнутри
        executor.shutdown()
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
        }

        // Останавливаем OkHttp
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PeerConnection
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val encoderFactory = if (USE_SOFTWARE_VIDEO_ENCODER) {
            SoftwareVideoEncoderFactory().also {
                log("WARN", "Software video encoder (H264 может быть недоступен)")
            }
        } else {
            DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true).also {
                log("INFO", "Hardware video encoder")
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

        log("INFO", "Video codecs: ${videoCodecs.ifEmpty { listOf("<none>") }.joinToString()} ; H264=$hasH264")
        log("INFO", "Audio codecs: ${audioCodecs.ifEmpty { listOf("<none>") }.joinToString()} ; OPUS=$hasOpus")
        if (!hasH264) log("WARN", "H264 отсутствует — будет использован доступный кодек")
    }

    private fun createPeerConnectionIfNeeded() {
        if (peerConnection != null) return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Для LAN-сценария можно оставить ALL, для NAT нужны TURN-серверы
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState) {
                    log("INFO", "Signaling: $s")
                }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                    log("INFO", "ICE: $s")
                    if (s == PeerConnection.IceConnectionState.FAILED) {
                        log("ERROR", "ICE connection failed — возможно, недоступен STUN/TURN")
                    }
                }
                override fun onIceConnectionReceivingChange(r: Boolean) = Unit
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                    log("INFO", "Gathering: $s")
                }
                override fun onIceCandidate(c: IceCandidate) {
                    sendIceCandidate(c)
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
                override fun onAddStream(s: org.webrtc.MediaStream) = Unit
                override fun onRemoveStream(s: org.webrtc.MediaStream) = Unit
                override fun onDataChannel(d: DataChannel) {
                    log("INFO", "Входящий DataChannel: ${d.label()}")
                }
                override fun onRenegotiationNeeded() {
                    log("INFO", "Renegotiation needed")
                }
                override fun onAddTrack(r: RtpReceiver, s: Array<out org.webrtc.MediaStream>) = Unit
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    log("INFO", "Connection: $s")
                    // На стороне STB рекомендуется отправлять PLI RTCP-пакеты
                    // для запроса keyframe при подключении.
                    // Хак с drop/restore битрейта вызывает keyframe storm на Qualcomm OMX.
                }
            }
        )
    }

    private fun configureVideoSenderBitrate(pc: PeerConnection) {
        val videoSender = pc.senders.firstOrNull {
            it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
        } ?: run {
            log("WARN", "Video sender not found")
            return
        }

        val parameters = videoSender.parameters

        // Для screen sharing: при недостатке полосы лучше снижать fps, чем разрешение.
        // Текст и UI-элементы теряют читаемость при снижении разрешения.
        parameters.degradationPreference =
            org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

        val encodings = parameters.encodings
        if (encodings.isNullOrEmpty()) {
            log("WARN", "Video sender has no encodings")
            return
        }

        encodings.forEachIndexed { index, encoding ->
            encoding.maxBitrateBps = TARGET_VIDEO_MAX_BITRATE_BPS
            encoding.minBitrateBps = TARGET_VIDEO_MIN_BITRATE_BPS
            log("INFO", "Encoding[$index]: min=${encoding.minBitrateBps}, max=${encoding.maxBitrateBps}")
        }

        val success = videoSender.setParameters(parameters)
        log("INFO", "Bitrate params applied=$success, degradation=MAINTAIN_RESOLUTION")
    }

    /**
     * Вставляет x-google-max-keyframe-interval в fmtp-строки H264 в SDP.
     *
     * Это говорит аппаратному энкодеру генерировать I-frame каждые N видео-фреймов.
     * При 30fps: 90 фреймов = 3 секунды.
     *
     * Преимущество перед PLI: не зависит от сети. Энкодер сам генерирует keyframe,
     * даже если RTCP-канал потерян. Overhead минимальный: один I-frame (~20KB)
     * каждые 3 секунды = ~53 kbps.
     */
    private fun String.setKeyframeInterval(maxFrames: Int): String {
        val param = "x-google-max-keyframe-interval=$maxFrames"
        val lines = split("\r\n").toMutableList()
        for (i in lines.indices) {
            val line = lines[i]
            // Ищем fmtp-строки для H264 payload types
            if (line.startsWith("a=fmtp:") && !line.contains(param)) {
                // Проверяем что это H264: ищем profile-level-id (есть только у H264)
                if (line.contains("profile-level-id")) {
                    lines[i] = "$line;$param"
                }
            }
        }
        return lines.joinToString("\r\n")
    }


    // ─────────────────────────────────────────────────────────────────────────
    // SDP / Offer
    // ─────────────────────────────────────────────────────────────────────────

    private fun createAndSendOffer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            // Телефон ОТПРАВЛЯЕТ видео и аудио, но НЕ принимает
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                var mungedSdp = sdp.description

                // Предпочтение H264 если доступен
                if (hasH264SenderCodec) {
                    mungedSdp = mungedSdp.preferCodec("H264", "video")
                }

                // Периодический keyframe каждые 90 фреймов (~3 секунды при 30fps).
                // Критично для WiFi: потеря одного P-frame ломает весь последующий поток,
                // а без keyframe interval энкодер работает с i-frame-interval=3600 (1 час).
                mungedSdp = mungedSdp.setKeyframeInterval(90)

                val local = SessionDescription(SessionDescription.Type.OFFER, mungedSdp)
                pc.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        sendMessage(JSONObject().put("type", "offer").put("sdp", local.description).toString())
                        log("INFO", "Offer отправлен (video=${if (hasH264SenderCodec) "H264" else "fallback"}, keyframeInterval=90)")
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?) { log("ERROR", "setLocalDescription: $e") }
                }, local)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(e: String?) { log("ERROR", "createOffer: $e") }
            override fun onSetFailure(e: String?) = Unit
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

    // ─────────────────────────────────────────────────────────────────────────
    // Signaling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSignalingPayload(payload: String) {
        executeOnWebRtcThread {
            val trimmed = payload.trim()
            if (trimmed.isEmpty()) return@executeOnWebRtcThread

            if (trimmed.equals("ping", ignoreCase = true) || trimmed.equals("pong", ignoreCase = true)) {
                log("INFO", "Keepalive: $trimmed")
                return@executeOnWebRtcThread
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
                log("INFO", "Доступ разрешён (access-granted)")
                accessState = AccessState.GRANTED
                createPeerConnectionIfNeeded()
                dispatchMain(onConnected)
            }

            "access-denied" -> {
                log("WARN", "Доступ отклонён (access-denied)")
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
                    override fun onSetFailure(e: String?) { log("ERROR", "setRemoteDescription: $e") }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            "ice-candidate" -> {
                if (accessState != AccessState.GRANTED) {
                    log("WARN", "Получен ICE до access-granted — игнорируем")
                    return
                }
                val cj = message.optJSONObject("candidate") ?: run {
                    log("WARN", "Пустой ICE candidate")
                    return
                }
                val candidate = IceCandidate(
                    cj.optString("sdpMid"),
                    cj.optInt("sdpMLineIndex", 0),
                    cj.optString("candidate")
                )
                val pc = peerConnection
                if (pc?.remoteDescription == null) {
                    pendingRemoteCandidates.add(candidate)
                    log("INFO", "ICE candidate в очередь (remoteDescription ещё не установлен)")
                } else {
                    val ok = pc.addIceCandidate(candidate)
                    log("INFO", "ICE candidate применён: $ok")
                }
            }

            else -> log("WARN", "Неизвестный тип сообщения: $type")
        }
    }

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        pendingRemoteCandidates.forEach {
            val ok = pc.addIceCandidate(it)
            log("INFO", "Queued candidate applied: $ok")
        }
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
        webSocket?.send(message) ?: log("ERROR", "WebSocket не подключен, сообщение не отправлено")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    private fun cleanupConnection() {
        executeOnWebRtcThread {
            runCatching { webSocket?.close(1000, "client cleanup") }
            webSocket = null
            accessState = AccessState.IDLE
            pendingRemoteCandidates.clear()
            stopStreamingInternal()
            dispatchMain(onDisconnected)
        }
    }

    /**
     * Внутренняя остановка трансляции (вызывается из executor).
     * Не вызывает onStreamingChanged — это делает [stopStreaming].
     */
    private fun stopStreamingInternal() {
        stopSystemAudioCapture()
        audioDataChannel?.close()
        audioDataChannel = null
        mediaProjection?.stop()
        mediaProjection = null

        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }

        screenCapturer = null
        videoTrack = null
        videoSource = null
        audioTrack = null
        audioSource = null
        surfaceTextureHelper = null

        runCatching {
            peerConnection?.senders?.forEach { peerConnection?.removeTrack(it) }
        }

        ProjectionForegroundService.stop(appContext)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Утилиты
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Выполняет блок в потоке WebRTC executor.
     * Безопасно для вызова после release().
     */
    private fun executeOnWebRtcThread(block: () -> Unit) {
        if (released) return
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "Executor уже остановлен, задача отброшена")
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