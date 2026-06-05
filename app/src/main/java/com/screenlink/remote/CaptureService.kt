package com.screenlink.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream

class CaptureService : Service() {

    companion object {
        var resultCode: Int = 0
        var data: Intent? = null
        @Volatile var running = false
        private const val CHANNEL = "screenlink"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ws: WebSocket? = null
    private val client = OkHttpClient()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var busy = false
    private var lastSent = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        running = true
        connectWebSocket()
        startCapture()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ScreenLink", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ScreenLink is sharing this screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun connectWebSocket() {
        val p = getSharedPreferences("screenlink", Context.MODE_PRIVATE)
        val relayUrl = (p.getString("relay", "") ?: "").trimEnd('/')
        val room = p.getString("room", "") ?: ""
        if (relayUrl.isEmpty() || room.isEmpty()) return
        val url = "$relayUrl/ws?room=$room&role=host"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                ControlService.instance?.onControl(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { }
        })
    }

    private fun startCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data ?: return) ?: return
        projection = proj

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = resources.displayMetrics
        val scale = 0.5
        val w = (dm.widthPixels * scale).toInt().coerceAtLeast(2)
        val h = (dm.heightPixels * scale).toInt().coerceAtLeast(2)
        val dpi = dm.densityDpi

        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = ir

        virtualDisplay = proj.createVirtualDisplay(
            "screenlink",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler
        )

        ir.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (!busy && now - lastSent >= 80) {
                    busy = true
                    lastSent = now
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmpW = w + (if (pixelStride > 0) rowPadding / pixelStride else 0)
                    val bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    val out = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val baos = ByteArrayOutputStream()
                    out.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    ws?.send(ByteString.of(*baos.toByteArray()))
                    if (out != bmp) out.recycle()
                    bmp.recycle()
                    busy = false
                }
            } catch (e: Exception) {
                busy = false
            } finally {
                image.close()
            }
        }, handler)
    }

    override fun onDestroy() {
        running = false
        try { ws?.close(1000, null) } catch (e: Exception) {}
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        try { thread?.quitSafely() } catch (e: Exception) {}
        super.onDestroy()
    }
}
