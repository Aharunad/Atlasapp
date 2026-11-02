package com.atlaslibrary.reader

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech as PocketTextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.TtsStream
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PocketPlaybackService : Service() {
    companion object {
        const val EVENT = "com.atlaslibrary.reader.TTS_EVENT"
        const val EVENT_JSON = "event_json"
        const val PREPARE = "com.atlaslibrary.reader.PREPARE_TTS"
        const val PLAY_BOOK = "com.atlaslibrary.reader.PLAY_BOOK"
        const val PREVIEW = "com.atlaslibrary.reader.PREVIEW_VOICE"
        const val PAUSE = "com.atlaslibrary.reader.PAUSE_TTS"
        const val RESUME = "com.atlaslibrary.reader.RESUME_TTS"
        const val STOP = "com.atlaslibrary.reader.STOP_TTS"
        const val REQUEST = "request_id"
        const val BOOK = "book_id"
        const val PAGE = "page"
        const val TITLE = "title"
        const val VOICE = "voice_id"
        const val RATE = "rate"
        private const val CHANNEL = "atlas_audiobook"
        private const val NOTIFICATION_ID = 7314
        private const val MIB = 1024L * 1024L
        private const val MODEL_NAME = "pocket_tts_en_int8.litertlm"
        private const val MODEL_URL = "https://huggingface.co/litert-community/pocket-tts/resolve/main/pocket_tts_en_int8.litertlm?download=true"
        private const val MIN_MODEL = 130L * MIB
        private const val MAX_MODEL = 190L * MIB
        val VOICES = listOf("alba", "marius", "javert", "jean", "fantine", "cosette", "eponine", "azelma")
        val LABELS = mapOf("alba" to "Alba", "marius" to "Marius", "javert" to "Javert", "jean" to "Jean", "fantine" to "Fantine", "cosette" to "Cosette", "eponine" to "Eponine", "azelma" to "Azelma")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)
    private val streamRef = AtomicReference<TtsStream?>(null)
    private val trackRef = AtomicReference<AudioTrack?>(null)
    private val pauseLock = ReentrantLock()
    private val pauseChanged = pauseLock.newCondition()
    private val modelFile by lazy { File(File(filesDir, "atlas-library/models").apply { mkdirs() }, MODEL_NAME) }
    private val cachePath by lazy { File(cacheDir, "pocket-tts-cache").apply { mkdirs() }.absolutePath }
    private val prefs by lazy { getSharedPreferences("atlas_library_settings_v1", Context.MODE_PRIVATE) }
    private var session: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var paused = false
    @Volatile private var state = "IDLE"
    @Volatile private var title = "Atlas Library"
    @Volatile private var bookId: String? = null
    @Volatile private var page = 1
    @Volatile private var pageCount = 1
    @Volatile private var requestId = ""
    @Volatile private var tts: PocketTextToSpeech? = null
    @Volatile private var loadedVoice = ""
    @Volatile private var loadedRate = -1f
    private var trackRate = 0
    private var submitted = 0L
    private var pausedByFocus = false

    override fun onCreate() {
        super.onCreate()
        makeChannel()
        audioManager = getSystemService(AudioManager::class.java)
        session = MediaSessionCompat(this, "AtlasLibraryAudiobook").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeNow()
                override fun onPause() = pauseNow()
                override fun onStop() = stopNow()
            })
            isActive = true
        }
        updateSession("IDLE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PREPARE -> {
                requestId = intent.getStringExtra(REQUEST).orEmpty()
                foreground("Preparing Pocket TTS")
                val token = generation.incrementAndGet()
                worker.execute {
                    try {
                        ensureModel()
                        ensureEngine("alba", 1f)
                        emit("prepared", JSONObject().put("ready", true).put("voices", VOICES))
                        setState("IDLE")
                    } catch (t: Throwable) {
                        emit("error", JSONObject().put("message", message(t)))
                        setState("ERROR")
                    } finally {
                        if (token == generation.get()) {
                            closeEngine()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            PLAY_BOOK -> {
                requestId = intent.getStringExtra(REQUEST).orEmpty()
                val id = intent.getStringExtra(BOOK).orEmpty()
                val from = intent.getIntExtra(PAGE, 1).coerceAtLeast(1)
                val name = intent.getStringExtra(TITLE).orEmpty().ifBlank { "Your PDF" }
                val voice = intent.getStringExtra(VOICE).orEmpty().ifBlank { "alba" }
                val rate = intent.getFloatExtra(RATE, 1f)
                title = name
                bookId = id
                foreground("Preparing audiobook")
                startBook(id, from, name, voice, rate)
            }
            PREVIEW -> {
                requestId = intent.getStringExtra(REQUEST).orEmpty()
                val voice = intent.getStringExtra(VOICE).orEmpty().ifBlank { "alba" }
                val rate = intent.getFloatExtra(RATE, 1f)
                title = "Voice preview · " + (LABELS[voice] ?: voice)
                bookId = null
                foreground("Preparing voice preview")
                startPreview(voice, rate)
            }
            PAUSE -> pauseNow()
            RESUME -> resumeNow()
            STOP -> stopNow()
        }
        return START_NOT_STICKY
    }

    private fun startBook(id: String, startPage: Int, name: String, voice: String, rate: Float) {
        require(id.matches(Regex("^[A-Za-z0-9_-]{8,64}$"))) { "Invalid PDF identifier." }
        require(voice in VOICES) { "That voice is not available in this Pocket TTS pack." }
        require(rate in 0.65f..1.5f) { "Choose a speed between 0.65× and 1.5×." }
        cancelCurrent()
        paused = false
        val token = generation.incrementAndGet()
        requestId = requestId
        title = name
        bookId = id
        submitted = 0L
        setState("PREPARING")
        holdCpu()
        worker.execute {
            var track: AudioTrack? = null
            try {
                val root = File(filesDir, "atlas-library/books").canonicalFile
                val dir = File(root, id).canonicalFile
                require(dir.parentFile == root && dir.isDirectory) { "This PDF is no longer in your library." }
                val meta = JSONObject(File(dir, "book.json").readText(Charsets.UTF_8))
                val pages = meta.optInt("pageCount", 0)
                require(pages > 0) { "This PDF does not contain any pages." }
                pageCount = pages
                ensureModel()
                val engine = ensureEngine(voice, rate)
                requestFocus()
                if (token == generation.get()) setState(if (paused) "PAUSED" else "PLAYING")
                for (p in startPage.coerceIn(1, pages)..pages) {
                    if (token != generation.get()) break
                    waitIfPaused(token)
                    if (token != generation.get()) break
                    page = p
                    prefs.edit().putString("lastBookId", id).putInt("lastPage", p).apply()
                    updateMetadata(name, p, pages)
                    emit("page", JSONObject().put("bookId", id).put("page", p).put("pageCount", pages).put("title", name))
                    val pageFile = File(dir, "pages/" + p + ".txt")
                    val text = if (pageFile.isFile) pageFile.readText(Charsets.UTF_8).trim() else ""
                    for (chunkText in splitText(text, 9000)) {
                        if (token != generation.get()) break
                        waitIfPaused(token)
                        val stream = engine.openStream(chunkText, SynthesisOptions(voiceId = voice), true)
                        streamRef.set(stream)
                        try {
                            while (token == generation.get()) {
                                waitIfPaused(token)
                                if (token != generation.get()) break
                                val audio = stream.readNext() ?: break
                                if (track == null || trackRate != audio.sampleRateHz) {
                                    drain(token, track)
                                    runCatching { track?.release() }
                                    track = makeTrack(audio.sampleRateHz)
                                    trackRate = audio.sampleRateHz
                                    trackRef.set(track)
                                    track.play()
                                }
                                var offset = 0
                                while (offset < audio.samples.size && token == generation.get()) {
                                    waitIfPaused(token)
                                    if (token != generation.get()) break
                                    val n = track.write(audio.samples, offset, audio.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                    if (n < 0) throw IllegalStateException("Android audio output failed ($n).")
                                    if (n == 0) Thread.sleep(5) else { offset += n; submitted += n }
                                }
                            }
                        } finally {
                            runCatching { stream.close() }
                            streamRef.compareAndSet(stream, null)
                        }
                    }
                    if (token == generation.get()) emit("page-complete", JSONObject().put("page", p).put("pageCount", pages))
                }
                if (token == generation.get()) {
                    drain(token, track)
                    emit("completed", JSONObject().put("bookId", id).put("page", pages).put("pageCount", pages))
                    setState("COMPLETED")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (t: Throwable) {
                if (token == generation.get()) {
                    emit("error", JSONObject().put("message", message(t)))
                    setState("ERROR")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } finally {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                releaseTrack()
                releaseCpu()
                abandonFocus()
            }
        }
    }

    private fun startPreview(voice: String, rate: Float) {
        require(voice in VOICES && rate in 0.65f..1.5f) { "Invalid voice preview settings." }
        cancelCurrent()
        paused = false
        val token = generation.incrementAndGet()
        submitted = 0L
        setState("PREPARING")
        holdCpu()
        worker.execute {
            var track: AudioTrack? = null
            try {
                ensureModel()
                val engine = ensureEngine(voice, rate)
                requestFocus()
                val stream = engine.openStream("Hello. This is a voice preview from Atlas Library.", SynthesisOptions(voiceId = voice), true)
                streamRef.set(stream)
                setState("PLAYING")
                while (token == generation.get()) {
                    waitIfPaused(token)
                    if (token != generation.get()) break
                    val audio = stream.readNext() ?: break
                    if (track == null) {
                        track = makeTrack(audio.sampleRateHz)
                        trackRate = audio.sampleRateHz
                        trackRef.set(track)
                        track.play()
                    }
                    var offset = 0
                    while (offset < audio.samples.size && token == generation.get()) {
                        waitIfPaused(token)
                        val n = track.write(audio.samples, offset, audio.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) throw IllegalStateException("Audio output failed ($n).")
                        if (n == 0) Thread.sleep(5) else { offset += n; submitted += n }
                    }
                }
                drain(token, track)
                if (token == generation.get()) {
                    emit("preview-complete", JSONObject().put("voice", voice))
                    setState("COMPLETED")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (t: Throwable) {
                if (token == generation.get()) {
                    emit("error", JSONObject().put("message", message(t)))
                    setState("ERROR")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } finally {
                streamRef.getAndSet(null)?.let { runCatching { it.close() } }
                releaseTrack()
                releaseCpu()
                abandonFocus()
            }
        }
    }

    private fun splitText(text: String, max: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val output = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val limit = minOf(start + max, text.length)
            var end = limit
            if (limit < text.length) {
                val candidate = text.lastIndexOfAny(charArrayOf(' ', '\n', '.', '!', '?'), limit)
                if (candidate > start + max / 2) end = candidate + 1
            }
            output.add(text.substring(start, end).trim())
            start = end
        }
        return output.filter { it.isNotBlank() }
    }

    private fun ensureModel() {
        if (modelFile.isFile && modelFile.length() in MIN_MODEL..MAX_MODEL) return
        modelFile.parentFile?.mkdirs()
        val part = File(modelFile.parentFile, MODEL_NAME + ".part")
        part.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000
            readTimeout = 45000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AtlasLibrary-Android/1.0")
        }
        try {
            connection.connect()
            val expected = connection.contentLengthLong
            if (expected > 0L && expected !in MIN_MODEL..MAX_MODEL) {
                throw IllegalStateException("Pocket TTS download size is unexpected (" + humanBytes(expected) + ").")
            }
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_MODEL) throw IllegalStateException("Pocket TTS download exceeded the safety limit.")
                        output.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - last > 400L) {
                            emit("download", JSONObject().put("downloaded", total).put("total", expected).put("message", "Downloading Pocket TTS"))
                            last = now
                        }
                    }
                    output.flush()
                }
            }
            if (part.length() !in MIN_MODEL..MAX_MODEL) throw IllegalStateException("Pocket TTS download is incomplete (" + humanBytes(part.length()) + "). Please retry.")
            if (modelFile.exists()) modelFile.delete()
            if (!part.renameTo(modelFile)) throw IllegalStateException("Could not install the Pocket TTS model.")
            emit("download", JSONObject().put("downloaded", modelFile.length()).put("total", modelFile.length()).put("message", "Pocket TTS ready"))
        } finally { connection.disconnect() }
    }

    private fun ensureEngine(voice: String, rate: Float): PocketTextToSpeech {
        val existing = tts
        if (existing != null && loadedVoice == voice && loadedRate == rate) return existing
        closeEngine()
        check(modelFile.isFile && modelFile.length() in MIN_MODEL..MAX_MODEL) { "Pocket TTS is not installed. Prepare the voice model first." }
        val options = TextToSpeechOptions(
            baseOptions = BaseOptions.builder().setModelAssetPath(modelFile.absolutePath).setDelegate(Delegate.CPU).build(),
            defaultVoiceId = voice,
            defaultLocale = "en-US",
            speakingRate = rate,
            cacheDirectory = cachePath
        )
        val created = PocketTextToSpeech.create(options)
        try {
            val available = created.listVoices().map { it.voiceId }.toSet()
            require(voice in available) { "Voice " + voice + " is not present in the installed Pocket TTS pack." }
            tts = created
            loadedVoice = voice
            loadedRate = rate
            emit("voice-ready", JSONObject().put("voiceId", voice).put("voiceName", LABELS[voice] ?: voice))
            return created
        } catch (t: Throwable) { runCatching { created.close() }; throw t }
    }

    private fun closeEngine() {
        tts?.let { runCatching { it.close() } }
        tts = null
        loadedVoice = ""
        loadedRate = -1f
    }

    private fun makeTrack(sampleRate: Int): AudioTrack {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minimum > 0) { "This Android device does not support the Pocket TTS audio format." }
        return AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    private fun drain(token: Long, audio: AudioTrack?) {
        if (audio == null) return
        while (token == generation.get() && audio.playbackHeadPosition.toLong() < submitted) {
            waitIfPaused(token)
            Thread.sleep(20)
        }
    }

    private fun waitIfPaused(token: Long) {
        pauseLock.lock()
        try {
            while (paused && token == generation.get()) pauseChanged.await(250, TimeUnit.MILLISECONDS)
        } finally {
            pauseLock.unlock()
        }
    }

    private fun pauseNow() {
        if (state != "PLAYING" && state != "PREPARING") return
        paused = true
        trackRef.get()?.let { runCatching { it.pause() } }
        releaseCpu()
        setState("PAUSED")
    }

    private fun resumeNow() {
        if (state != "PAUSED" && state != "PREPARING") return
        paused = false
        pauseLock.lock()
        try { pauseChanged.signalAll() } finally { pauseLock.unlock() }
        trackRef.get()?.let { runCatching { it.play() } }
        holdCpu()
        if (state == "PAUSED") setState("PLAYING")
    }

    private fun stopNow() {
        cancelCurrent()
        paused = false
        pauseLock.lock()
        try { pauseChanged.signalAll() } finally { pauseLock.unlock() }
        setState("STOPPED")
        emit("stopped", JSONObject().put("bookId", bookId).put("page", page))
        releaseCpu()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelCurrent() {
        generation.incrementAndGet()
        streamRef.getAndSet(null)?.let { runCatching { it.cancel() }; runCatching { it.close() } }
        releaseTrack()
    }

    private fun releaseTrack() {
        trackRef.getAndSet(null)?.let { audio ->
            runCatching { audio.pause() }; runCatching { audio.flush() }
            runCatching { audio.stop() }; runCatching { audio.release() }
        }
        trackRate = 0
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { pausedByFocus = true; pauseNow() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> trackRef.get()?.setVolume(0.25f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    trackRef.get()?.setVolume(1f)
                    if (pausedByFocus) { pausedByFocus = false; resumeNow() }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener).setWillPauseWhenDucked(true).build()
            manager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private fun holdCpu() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":AtlasLibraryPlayback")
                wakeLock?.acquire(10L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseCpu() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
    }

    private fun foreground(subtitle: String) {
        title = subtitle
        startForeground(NOTIFICATION_ID, notification())
    }

    private fun setState(value: String) {
        state = value
        updateSession(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        emit("state", JSONObject().put("state", value).put("title", title).put("page", page).put("pageCount", pageCount))
    }

    private fun updateMetadata(name: String, p: Int, total: Int) {
        title = name
        session?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Atlas Library")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Page " + p + " of " + total).build())
        setState("PLAYING")
    }

    private fun updateSession(value: String) {
        val mediaState = when (value) {
            "PLAYING" -> PlaybackStateCompat.STATE_PLAYING
            "PAUSED" -> PlaybackStateCompat.STATE_PAUSED
            "PREPARING" -> PlaybackStateCompat.STATE_BUFFERING
            "COMPLETED", "STOPPED" -> PlaybackStateCompat.STATE_STOPPED
            "ERROR" -> PlaybackStateCompat.STATE_ERROR
            else -> PlaybackStateCompat.STATE_NONE
        }
        session?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(mediaState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (value == "PLAYING") 1f else 0f).build())
    }

    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val isPaused = state == "PAUSED"
        val action = if (isPaused) RESUME else PAUSE
        val icon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val label = if (isPaused) "Resume" else "Pause"
        val toggle = PendingIntent.getService(this, 1, Intent(this, PocketPlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, PocketPlaybackService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val subtitle = if (pageCount > 1) "Page " + page + " of " + pageCount + " · " + if (isPaused) "Paused" else "Audio reading" else "Atlas Library audiobook"
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(subtitle).setContentIntent(launch)
            .setOnlyAlertOnce(true).setOngoing(state != "COMPLETED" && state != "STOPPED" && state != "ERROR")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(NotificationCompat.Action(icon, label, toggle))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop))
            .setStyle(MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Atlas Library audiobook", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Background PDF narration and playback controls"
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun emit(type: String, data: JSONObject) {
        val event = JSONObject(data.toString()).put("type", type).put("state", state).put("requestId", requestId)
        sendBroadcast(Intent(EVENT).setPackage(packageName).putExtra(EVENT_JSON, event.toString()))
    }

    private fun humanBytes(bytes: Long): String {
        val labels = arrayOf("B", "KB", "MB", "GB")
        var n = bytes.toDouble(); var index = 0
        while (n >= 1024 && index < labels.lastIndex) { n /= 1024; index++ }
        return String.format(Locale.US, "%.1f %s", n, labels[index])
    }

    private fun message(t: Throwable) = (t.message ?: "Pocket TTS could not complete playback.").take(400)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelCurrent()
        runCatching { tts?.close() }
        tts = null
        releaseCpu()
        abandonFocus()
        session?.isActive = false
        session?.release()
        session = null
        worker.shutdownNow()
        super.onDestroy()
    }
}