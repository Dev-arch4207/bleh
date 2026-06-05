package com.screenlink.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
    }

    private val points = ArrayList<PointF>()
    private var down = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun onControl(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "down" -> { points.clear(); points.add(point(o)); down = true }
            "move" -> { if (down) points.add(point(o)) }
            "up" -> {
                if (down) {
                    points.add(point(o))
                    dispatchPath(ArrayList(points))
                    down = false
                }
            }
            "global" -> when (o.optString("action")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            "scroll" -> scroll(o.optString("dir"))
        }
    }

    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun point(o: JSONObject): PointF {
        val x = (o.optDouble("x", 0.0) * screenW()).toFloat()
        val y = (o.optDouble("y", 0.0) * screenH()).toFloat()
        return PointF(x, y)
    }

    private fun dispatchPath(pts: List<PointF>) {
        if (pts.isEmpty()) return
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        var moved = false
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
            if (pts[i].x != pts[0].x || pts[i].y != pts[0].y) moved = true
        }
        if (!moved) path.lineTo(pts[0].x + 1f, pts[0].y + 1f)
        val duration = if (pts.size <= 2) 60L else 250L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun scroll(dir: String) {
        val cx = screenW() / 2f
        val path = Path()
        if (dir == "down") {
            path.moveTo(cx, screenH() * 0.70f); path.lineTo(cx, screenH() * 0.30f)
        } else {
            path.moveTo(cx, screenH() * 0.30f); path.lineTo(cx, screenH() * 0.70f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 220L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
