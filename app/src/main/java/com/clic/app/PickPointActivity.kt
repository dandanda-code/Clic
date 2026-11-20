package com.clic.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/** Pantalla semitransparente: el usuario toca donde quiere que se haga clic. */
class PickPointActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(PickView())
        Toast.makeText(this, "Toca el punto donde quieres que se haga clic", Toast.LENGTH_LONG).show()
    }

    private inner class PickView : View(this@PickPointActivity) {
        private val paint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(140, 0, 0, 0))
            canvas.drawText("Toca el punto del clic", width / 2f, height / 2f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val settings = Settings(context)
                // rawX/rawY = coordenadas absolutas de pantalla, las mismas que usa dispatchGesture.
                settings.tapX = event.rawX.toInt()
                settings.tapY = event.rawY.toInt()
                finish()
            }
            return true
        }
    }
}
