package com.riyad.banglalivesubtitles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that shows floating Japanese + Bangla subtitles over any app.
 *
 * Two modes:
 * - Phone audio: captures the phone's internal playback audio (e.g. a Voov call)
 *   with MediaProjection + AudioPlaybackCaptureConfiguration, then recognises
 *   Japanese offline with Vosk. No microphone is used in this mode.
 * - Microphone: uses Android's built-in SpeechRecognizer on the mic.
 *
 * Why Vosk for phone audio: Android's SpeechRecognizer only listens to the
 * microphone — there is no public API to feed it captured playback audio.
 * Vosk is free, open source (Apache 2.0) and works on raw PCM, so it is the
 * only free way to recognise the captured call audio on-device.
 */
class SubtitleService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val EXTRA_TARGET_LANG = "target_lang"
        const val EXTRA_TEXT_SIZE = "text_size"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_SERVICE_STOPPED = "com.riyad.banglalivesubtitles.SERVICE_STOPPED"
        const val EXTRA_ERROR = "error"

        private const val MAX_MIC_RETRIES = 5

        const val MODE_PHONE_AUDIO = "phone_audio"
        const val MODE_MIC = "mic"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_service"
        private const val SAMPLE_RATE = 16000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val translateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var running = false
    private var stopError: String? = null
    private var mode = MODE_PHONE_AUDIO
    private var sourceLang = "ja"
    private var targetLang = "bn"
    private var textSizeSp = 20f

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var originalTextView: TextView? = null
    private var translatedTextView: TextView? = null

    // Phone-audio capture
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null

    // Microphone recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var micRetries = 0

    // Debounced translation of partial results
    private var lastPartialText = ""
    private val translatePartialRunnable = Runnable {
        if (running && lastPartialText.isNotBlank()) {
            translateAndShow(lastPartialText)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (running) return START_STICKY

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PHONE_AUDIO
        sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "ja"
        targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "bn"
        textSizeSp = intent.getFloatExtra(EXTRA_TEXT_SIZE, 20f)

        val serviceType = if (mode == MODE_PHONE_AUDIO) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIFICATION_ID, buildNotification(), serviceType)

        running = true
        stopError = null
        addOverlay()

        if (mode == MODE_PHONE_AUDIO) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData == null) {
                showToast("Screen capture অনুমতি পাওয়া যায়নি")
                stopSelf()
                return START_NOT_STICKY
            }
            startPhoneAudioMode(resultCode, resultData)
        } else {
            startMicMode()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(translatePartialRunnable)
        stopPhoneAudioMode()
        stopMicMode()
        removeOverlay()
        translateExecutor.shutdownNow()
        stopForeground(true)
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).putExtra(EXTRA_ERROR, stopError))
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null)
        overlayView = view
        originalTextView = view.findViewById(R.id.originalText)
        translatedTextView = view.findViewById(R.id.translatedText)
        translatedTextView?.textSize = textSizeSp
        originalTextView?.textSize = textSizeSp * 0.7f

        view.findViewById<View>(R.id.closeButton).setOnClickListener { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 150
        overlayParams = params
        view.setOnTouchListener(dragTouchListener)
        wm.addView(view, params)
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            // View was already removed.
        }
        overlayView = null
        windowManager = null
    }

    private var dragStartOffsetX = 0
    private var dragStartOffsetY = 0

    private val dragTouchListener = View.OnTouchListener { _, event ->
        val params = overlayParams ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartOffsetX = params.x - event.rawX.toInt()
                dragStartOffsetY = params.y - event.rawY.toInt()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = event.rawX.toInt() + dragStartOffsetX
                params.y = event.rawY.toInt() + dragStartOffsetY
                try {
                    windowManager?.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    // Overlay gone.
                }
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------- phone audio mode

    private fun startPhoneAudioMode(resultCode: Int, resultData: Intent) {
        captureThread = Thread {
            try {
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                // Only USAGE_MEDIA, USAGE_GAME and USAGE_UNKNOWN can be
                // captured (Android docs). Voice-call audio
                // (USAGE_VOICE_COMMUNICATION) is NOT capturable, and asking
                // for it makes the audio policy registration fail.
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer * 4)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                audioRecord = record
                record.startRecording()

                // Load the offline Japanese model (blocking, so it runs here).
                val model = Model(VoskModelManager.modelDir(this).absolutePath)
                voskModel = model
                val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                voskRecognizer = recognizer

                postStatus("শুনছি…")
                val buffer = ByteArray(4096)
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val isFinal = recognizer.acceptWaveForm(buffer, read)
                        if (isFinal) {
                            val text = parseVoskText(recognizer.result)
                            if (text.isNotBlank()) {
                                mainHandler.post { onFinalText(text) }
                            }
                        } else {
                            val partial = parseVoskPartial(recognizer.partialResult)
                            if (partial.isNotBlank()) {
                                mainHandler.post { onPartialText(partial) }
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                val friendly = if (e.message?.contains("audio policy", ignoreCase = true) == true) {
                    "ফোন অডিও চালু হয়নি — অডিও অনুমতি দাওনি বা এই ফোন সাপোর্ট করে না। মাইক্রোফোন মোড try করো।"
                } else {
                    "ফোন অডিও চালু হয়নি: ${e.message}"
                }
                stopError = friendly
                mainHandler.post {
                    showToast(friendly)
                    stopSelf()
                }
            }
        }
        captureThread?.start()
    }

    private fun stopPhoneAudioMode() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
        }
        audioRecord = null
        try {
            voskRecognizer?.close()
        } catch (e: Exception) {
        }
        voskRecognizer = null
        try {
            voskModel?.close()
        } catch (e: Exception) {
        }
        voskModel = null
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
        }
        mediaProjection = null
        captureThread = null
    }

    private fun parseVoskText(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "")
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ mic mode

    private fun startMicMode() {
        micRetries = 0
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showToast("এই ফোনে Google speech recognition পাওয়া যায়নি")
            stopSelf()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(micListener)
        }
        startListeningMic()
    }

    /**
     * ERROR_CLIENT is NOT a permission problem — the recognizer itself failed
     * to start (often because another app, e.g. a Voov call, is holding the
     * mic, or the recognizer service hiccuped on a cold start). Recreate the
     * recognizer and retry a few times instead of stopping at once.
     */
    private fun retryMicWithFreshRecognizer() {
        if (!running) return
        micRetries++
        if (micRetries > MAX_MIC_RETRIES) {
            val msg = "মাইক চালু হলো না — Voov call চলাকালীন মাইক ব্যস্ত থাকলে এমন হয়। কল স্পিকারে দিয়ে আবার try করো।"
            stopError = msg
            showToast(msg)
            stopSelf()
            return
        }
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            stopMicMode()
            startMicMode()
        }, 600)
    }

    private fun startListeningMic() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTagFor(sourceLang))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            showToast("মাইক চালু হয়নি: ${e.message}")
            stopSelf()
        }
    }

    private fun stopMicMode() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
        }
        speechRecognizer = null
    }

    private val micListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            postStatus("শুনছি…")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // Keep listening continuously.
            mainHandler.postDelayed({ startListeningMic() }, 150)
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    showToast("মাইক অনুমতি দরকার")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    retryMicWithFreshRecognizer()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    mainHandler.postDelayed({
                        stopMicMode()
                        if (running) startMicMode()
                    }, 400)
                }
                else -> {
                    // No match, timeout, network hiccup: just listen again.
                    mainHandler.postDelayed({ startListeningMic() }, 400)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            micRetries = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onFinalText(text)
            mainHandler.postDelayed({ startListeningMic() }, 150)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onPartialText(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun speechTagFor(code: String): String {
        return when (code) {
            "ja" -> "ja-JP"
            "en" -> "en-US"
            "bn" -> "bn-BD"
            else -> "ja-JP"
        }
    }

    // ------------------------------------------------------- text + translate

    private fun onPartialText(text: String) {
        originalTextView?.text = text
        lastPartialText = text
        mainHandler.removeCallbacks(translatePartialRunnable)
        mainHandler.postDelayed(translatePartialRunnable, 700)
    }

    private fun onFinalText(text: String) {
        originalTextView?.text = text
        lastPartialText = text
        mainHandler.removeCallbacks(translatePartialRunnable)
        translateAndShow(text)
    }

    private fun translateAndShow(text: String) {
        val from = sourceLang
        val to = targetLang
        translateExecutor.execute {
            val translated = Translator.translate(text, from, to)
            mainHandler.post {
                if (!running) return@post
                translatedTextView?.text = translated ?: "অনুবাদ হয়নি"
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun postStatus(text: String) {
        mainHandler.post { originalTextView?.text = text }
    }

    private fun showToast(text: String) {
        mainHandler.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live subtitles",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val title = if (mode == MODE_PHONE_AUDIO) {
            "Bangla Live Subtitles — ফোন অডিও"
        } else {
            "Bangla Live Subtitles — মাইক"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("সাবটাইটেল চালু আছে — বন্ধ করতে উপরের ✕ চাপো")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
