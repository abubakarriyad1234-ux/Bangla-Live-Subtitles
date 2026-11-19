package com.riyad.banglalivesubtitles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_PROJECTION = 1002
        private const val REQUEST_AUDIO = 1003
        private const val REQUEST_NOTIFICATION = 1004
    }

    private lateinit var modeGroup: RadioGroup
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var sizeSeekBar: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var modelDownloadButton: Button
    private lateinit var modelProgressBar: ProgressBar
    private lateinit var startStopButton: Button
    private lateinit var statusText: TextView

    private var running = false
    private var pendingMicStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeGroup = findViewById(R.id.modeGroup)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        targetSpinner = findViewById(R.id.targetSpinner)
        sizeSeekBar = findViewById(R.id.sizeSeekBar)
        sizeLabel = findViewById(R.id.sizeLabel)
        modelStatusText = findViewById(R.id.modelStatusText)
        modelDownloadButton = findViewById(R.id.modelDownloadButton)
        modelProgressBar = findViewById(R.id.modelProgressBar)
        startStopButton = findViewById(R.id.startStopButton)
        statusText = findViewById(R.id.statusText)

        setupSpinners()
        setupSizeSeekBar()

        modeGroup.setOnCheckedChangeListener { _, _ -> refreshModelSection() }
        modelDownloadButton.setOnClickListener { downloadModel() }
        startStopButton.setOnClickListener { onStartStopClicked() }

        refreshModelSection()
    }

    override fun onResume() {
        super.onResume()
        refreshModelSection()
    }

    private fun setupSpinners() {
        val sourceAdapter = ArrayAdapter.createFromResource(
            this, R.array.source_languages, android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceSpinner.adapter = sourceAdapter
        sourceSpinner.setSelection(0) // Japanese

        val targetAdapter = ArrayAdapter.createFromResource(
            this, R.array.target_languages, android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        targetSpinner.adapter = targetAdapter
        targetSpinner.setSelection(0) // Bengali
    }

    private fun setupSizeSeekBar() {
        sizeSeekBar.max = 16 // 14sp .. 30sp
        sizeSeekBar.progress = 6 // 20sp
        updateSizeLabel()
        sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSizeLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun textSizeSp(): Float = (14 + sizeSeekBar.progress).toFloat()

    private fun updateSizeLabel() {
        sizeLabel.text = "লেখার সাইজ: ${textSizeSp().toInt()}sp"
    }

    private fun isPhoneAudioMode(): Boolean {
        return modeGroup.checkedRadioButtonId == R.id.modePhoneAudio
    }

    private fun sourceCode(): String {
        val codes = resources.getStringArray(R.array.source_language_codes)
        return codes[sourceSpinner.selectedItemPosition]
    }

    private fun targetCode(): String {
        val codes = resources.getStringArray(R.array.target_language_codes)
        return codes[targetSpinner.selectedItemPosition]
    }

    private fun refreshModelSection() {
        if (!isPhoneAudioMode()) {
            modelStatusText.visibility = View.GONE
            modelDownloadButton.visibility = View.GONE
            modelProgressBar.visibility = View.GONE
            return
        }
        modelStatusText.visibility = View.VISIBLE
        if (VoskModelManager.isModelReady(this)) {
            modelStatusText.text = "জাপানি মডেল তৈরি ✓ (ফোন অডিও মোড চলবে)"
            modelDownloadButton.visibility = View.GONE
            modelProgressBar.visibility = View.GONE
        } else {
            modelStatusText.text = "ফোন অডিও মোডের জন্য জাপানি মডেল (প্রায় 48 MB) একবার ডাউনলোড করতে হবে।"
            modelDownloadButton.visibility = View.VISIBLE
            modelProgressBar.visibility = View.GONE
        }
    }

    private fun downloadModel() {
        modelDownloadButton.isEnabled = false
        modelProgressBar.visibility = View.VISIBLE
        modelProgressBar.progress = 0
        modelStatusText.text = "মডেল ডাউনলোড হচ্ছে… 0%"
        VoskModelManager.downloadModel(
            this,
            onProgress = { percent ->
                runOnUiThread {
                    modelProgressBar.progress = percent
                    modelStatusText.text = "মডেল ডাউনলোড হচ্ছে… $percent%"
                }
            },
            onDone = {
                runOnUiThread {
                    modelProgressBar.visibility = View.GONE
                    modelDownloadButton.isEnabled = true
                    refreshModelSection()
                    Toast.makeText(this, "মডেল তৈরি ✓", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { message ->
                runOnUiThread {
                    modelProgressBar.visibility = View.GONE
                    modelDownloadButton.isEnabled = true
                    modelStatusText.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun onStartStopClicked() {
        if (running) {
            stopService(Intent(this, SubtitleService::class.java))
            running = false
            startStopButton.text = "শুরু করো"
            statusText.text = "বন্ধ আছে"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "প্রথমে 'অন্য অ্যাপের উপরে দেখানো' অনুমতি দাও", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION
            )
            // Notification permission is not blocking; continue below.
        }
        if (isPhoneAudioMode()) {
            startPhoneAudioMode()
        } else {
            startMicMode()
        }
    }

    private fun startPhoneAudioMode() {
        if (sourceCode() != "ja") {
            sourceSpinner.setSelection(0)
            Toast.makeText(
                this,
                "ফোন অডিও মোড v1 শুধু জাপানি শুনতে পারে — মূল ভাষা জাপানি করা হলো",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!VoskModelManager.isModelReady(this)) {
            Toast.makeText(this, "আগে জাপানি মডেল ডাউনলোড করো", Toast.LENGTH_LONG).show()
            refreshModelSection()
            return
        }
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_PROJECTION
        )
    }

    private fun startMicMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingMicStart = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO
            )
            return
        }
        startSubtitleService(null, null)
    }

    private fun startSubtitleService(resultCode: Int?, resultData: Intent?) {
        val intent = Intent(this, SubtitleService::class.java).apply {
            putExtra(
                SubtitleService.EXTRA_MODE,
                if (isPhoneAudioMode()) SubtitleService.MODE_PHONE_AUDIO else SubtitleService.MODE_MIC
            )
            putExtra(SubtitleService.EXTRA_SOURCE_LANG, sourceCode())
            putExtra(SubtitleService.EXTRA_TARGET_LANG, targetCode())
            putExtra(SubtitleService.EXTRA_TEXT_SIZE, textSizeSp())
            if (resultCode != null) putExtra(SubtitleService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) putExtra(SubtitleService.EXTRA_RESULT_DATA, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        running = true
        startStopButton.text = "বন্ধ করো"
        statusText.text = if (isPhoneAudioMode()) {
            "চলছে — ফোন অডিও শুনছি"
        } else {
            "চলছে — মাইক শুনছি"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "অন্য অ্যাপের উপরে দেখানো ছাড়া সাবটাইটেল আসবে না", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    startSubtitleService(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen capture অনুমতি দাওনি", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingMicStart) {
                    pendingMicStart = false
                    startSubtitleService(null, null)
                }
            } else {
                pendingMicStart = false
                Toast.makeText(this, "মাইক অনুমতি ছাড়া মাইক মোড চলবে না", Toast.LENGTH_LONG).show()
            }
        }
    }
}
