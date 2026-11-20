package com.clic.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }

    private lateinit var settings: Settings
    private lateinit var statusText: TextView
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        statusText = findViewById(R.id.statusText)
        xInput = findViewById(R.id.xInput)
        yInput = findViewById(R.id.yInput)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Busca \"Clic\" en la lista y actívalo", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.pickPointButton).setOnClickListener {
            startActivity(Intent(this, PickPointActivity::class.java))
        }

        xInput.addTextChangedListener(intWatcher { settings.tapX = it })
        yInput.addTextChangedListener(intWatcher { settings.tapY = it })

        val sensitivityLabel = findViewById<TextView>(R.id.sensitivityLabel)
        findViewById<SeekBar>(R.id.sensitivitySeek).apply {
            progress = settings.sensitivity
            sensitivityLabel.text = "Sensibilidad: ${settings.sensitivity}%"
            setOnSeekBarChangeListener(seekListener {
                settings.sensitivity = it
                sensitivityLabel.text = "Sensibilidad: $it%"
            })
        }

        val cooldownLabel = findViewById<TextView>(R.id.cooldownLabel)
        findViewById<SeekBar>(R.id.cooldownSeek).apply {
            progress = settings.cooldownTenths
            cooldownLabel.text = cooldownText(settings.cooldownTenths)
            setOnSeekBarChangeListener(seekListener {
                settings.cooldownTenths = it
                cooldownLabel.text = cooldownText(it)
            })
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { startDetection() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            if (FlashDetectorService.isRunning) {
                startService(Intent(this, FlashDetectorService::class.java).setAction(FlashDetectorService.ACTION_STOP))
            }
            handler.postDelayed({ refreshStatus() }, 300)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Puede haber cambiado desde PickPointActivity.
        setTextIfDifferent(xInput, settings.tapX)
        setTextIfDifferent(yInput, settings.tapY)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun startDetection() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Primero activa el servicio de accesibilidad de Clic", Toast.LENGTH_LONG).show()
            startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Se necesita permiso de captura de pantalla", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FlashDetectorService::class.java)
            .setAction(FlashDetectorService.ACTION_START)
            .putExtra(FlashDetectorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(FlashDetectorService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Detección iniciada. Ya puedes ir a otra app.", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (ClickAccessibilityService.isRunning) return true
        val enabled = SystemSettings.Secure.getString(
            contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, ClickAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refreshStatus() {
        val acc = if (isAccessibilityEnabled()) "✅ Accesibilidad activada" else "❌ Accesibilidad desactivada"
        val det = if (FlashDetectorService.isRunning)
            "✅ Detectando (clics: ${FlashDetectorService.clickCount})" else "⏸ Detección detenida"
        statusText.text = "$acc\n$det"
    }

    private fun cooldownText(tenths: Int) =
        "Espera mínima entre clics: %.1f s".format(tenths.coerceAtLeast(1) / 10f)

    private fun setTextIfDifferent(input: EditText, value: Int) {
        val text = if (value >= 0) value.toString() else ""
        if (input.text.toString() != text) input.setText(text)
    }

    private fun intWatcher(onValue: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            onValue(s?.toString()?.toIntOrNull() ?: -1)
        }
    }

    private fun seekListener(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onValue(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
