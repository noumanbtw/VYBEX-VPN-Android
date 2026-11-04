package com.vybex.vpn

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Native Android starter UI. VPN connection is intentionally unavailable until a real
 * WireGuard backend, authenticated config service, and provisioned gateways are connected. */
class MainActivity : Activity() {
    private val bg = Color.rgb(8, 11, 22)
    private val cyan = Color.rgb(37, 217, 245)
    private val muted = Color.rgb(157, 174, 199)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(42), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        val brand = TextView(this).apply {
            text = "VYBEX"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        }
        root.addView(brand, matchWrap())

        val subtitle = TextView(this).apply {
            text = "PRIVATE CONNECTION. YOUR CONTROL."
            textSize = 10f
            letterSpacing = 0.18f
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, matchWrap())
        addSpace(root, 54)

        val status = TextView(this).apply {
            text = "VPN ENGINE NOT CONNECTED"
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap())
        addSpace(root, 14)

        val connect = TextView(this).apply {
            text = "VPN SETUP REQUIRED"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(24, 35, 55))
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        root.addView(connect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)))
        addSpace(root, 22)

        val message = TextView(this).apply {
            text = "This installable Android starter is ready for the real VPN engine. To enable secure connections, VYBEX still needs a WireGuard tunnel integration and verified VPN server configurations. No fake connection or server list is shown."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        root.addView(message, matchWrap())
        addSpace(root, 34)

        val serverInfo = TextView(this).apply {
            text = "SERVER LOCATIONS\n\nNo live gateways configured yet"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(15, 22, 39))
        }
        root.addView(serverInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addSpace(root, 20)
        val footer = TextView(this).apply {
            text = "FREE FIRST  •  ANDROID EDITION"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        root.addView(footer, matchWrap())
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun addSpace(parent: LinearLayout, height: Int) {
        parent.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
}
