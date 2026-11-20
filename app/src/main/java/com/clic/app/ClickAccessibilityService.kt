package com.clic.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Servicio de accesibilidad que realiza los toques en pantalla. */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Toca la pantalla en (x, y). Devuelve false si el gesto no se pudo enviar. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
