package com.jezacapital.quarterly

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Tiny launcher screen so the app shows in the app drawer and can pin the widget with one tap. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            setBackgroundColor(Color.parseColor("#0B1F14"))
        }

        val title = TextView(this).apply {
            text = "JEZAcapital"
            textSize = 30f
            setTextColor(Color.parseColor("#69F0AE"))
            gravity = Gravity.CENTER
        }

        val info = TextView(this).apply {
            text = "90-minute Quarterly Theory widget\n(New York time)\n\n" +
                "Tap the button below to add it to your home screen.\n\n" +
                "Or: long-press the home screen > Widgets > JEZAcapital Quarters."
            textSize = 16f
            setTextColor(Color.parseColor("#C8E6C9"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 48)
        }

        val button = Button(this).apply {
            text = "Add widget to home screen"
            setOnClickListener {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                if (manager.isRequestPinAppWidgetSupported) {
                    manager.requestPinAppWidget(
                        ComponentName(this@MainActivity, QuarterWidgetProvider::class.java), null, null
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Long-press home screen > Widgets > JEZAcapital Quarters",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(button)
        setContentView(root)
    }
}
