package com.screenlink.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val reqCapture = 1001
    private lateinit var mpm: MediaProjectionManager
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = getSharedPreferences("screenlink", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2002)
        }

        findViewById<Button>(R.id.accBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            val relay = findViewById<EditText>(R.id.relay).text.toString().trim()
            val room = findViewById<EditText>(R.id.room).text.toString().trim().uppercase()
            val helper = findViewById<EditText>(R.id.helper).text.toString().trim()
            if (relay.isEmpty() || room.isEmpty()) {
                Toast.makeText(this, "Enter the relay URL and a room code.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("relay", toWs(relay))
                .putString("room", room)
                .putString("helper", if (helper.isEmpty()) "your helper" else helper)
                .apply()
            Toast.makeText(this, "Saved. They now just tap the big button.", Toast.LENGTH_LONG).show()
            showHome()
        }

        findViewById<Button>(R.id.bigBtn).setOnClickListener {
            if (CaptureService.running) {
                stopSharing()
            } else {
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
        }

        findViewById<TextView>(R.id.title).setOnLongClickListener { showSetup(); true }

        if ((prefs.getString("relay", "") ?: "").isEmpty()) showSetup() else showHome()
    }

    override fun onResume() {
        super.onResume()
        if (findViewById<View>(R.id.homeGroup).visibility == View.VISIBLE) updateBigButton()
    }

    private fun showSetup() {
        findViewById<View>(R.id.setupGroup).visibility = View.VISIBLE
        findViewById<View>(R.id.homeGroup).visibility = View.GONE
        findViewById<EditText>(R.id.relay).setText(prefs.getString("relay", ""))
        findViewById<EditText>(R.id.room).setText(prefs.getString("room", ""))
        findViewById<EditText>(R.id.helper).setText(prefs.getString("helper", ""))
    }

    private fun showHome() {
        findViewById<View>(R.id.setupGroup).visibility = View.GONE
        findViewById<View>(R.id.homeGroup).visibility = View.VISIBLE
        val helper = prefs.getString("helper", "your helper")
        findViewById<TextView>(R.id.bigLabel).text =
            "Tap the button to let $helper see and fix your phone."
        updateBigButton()
    }

    private fun updateBigButton() {
        val b = findViewById<Button>(R.id.bigBtn)
        val s = findViewById<TextView>(R.id.status)
        if (CaptureService.running) {
            b.text = "● SHARING — tap to STOP"
            b.setBackgroundColor(0xFFE53935.toInt())
            s.text = "Your helper can see your screen now."
        } else {
            b.text = "START — let them help"
            b.setBackgroundColor(0xFF2FB870.toInt())
            s.text = "Not sharing."
        }
    }

    private fun stopSharing() {
        stopService(Intent(this, CaptureService::class.java))
        CaptureService.running = false
        updateBigButton()
    }

    private fun toWs(url: String): String {
        var s = url
        s = when {
            s.startsWith("https://") -> "wss://" + s.substring(8)
            s.startsWith("http://") -> "ws://" + s.substring(7)
            s.startsWith("ws://") || s.startsWith("wss://") -> s
            else -> "wss://$s"
        }
        return s.trimEnd('/')
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.resultCode = resultCode
                CaptureService.data = data
                val i = Intent(this, CaptureService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                val b = findViewById<Button>(R.id.bigBtn)
                b.text = "● SHARING — tap to STOP"
                b.setBackgroundColor(0xFFE53935.toInt())
                findViewById<TextView>(R.id.status).text = "Your helper can see your screen now."
            } else {
                Toast.makeText(this, "Screen sharing was cancelled.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
