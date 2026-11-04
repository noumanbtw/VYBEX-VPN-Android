package com.vybex.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * VYBEX VPN — working WireGuard client.
 *
 * Real tunnel via the official WireGuard embeddable tunnel library
 * (com.wireguard.android:tunnel, GoBackend). No servers are bundled and
 * nothing is faked: the user imports a standard WireGuard .conf profile
 * (from their own server or any provider) and the app brings that tunnel
 * up through Android's VpnService.
 */
class MainActivity : Activity() {

    private val bg = Color.rgb(8, 11, 22)
    private val panel = Color.rgb(15, 22, 39)
    private val cyan = Color.rgb(37, 217, 245)
    private val green = Color.rgb(52, 224, 140)
    private val red = Color.rgb(255, 99, 99)
    private val muted = Color.rgb(157, 174, 199)
    private val white = Color.WHITE

    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var connectButton: Button
    private lateinit var selectedView: TextView
    private lateinit var profilesBox: LinearLayout

    private var tunnel: WgTunnel? = null
    private var connected = false
    private var busy = false
    private var pendingConnect = false

    private val profilesDir: File get() = File(filesDir, "profiles").apply { mkdirs() }
    private val prefs by lazy { getSharedPreferences("vybex", MODE_PRIVATE) }

    inner class WgTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            mainHandler.post { onTunnelState(newState) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        backend = GoBackend(applicationContext)
        buildUi()
        refreshProfiles()
        syncTunnelState()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "VYBEX"; textSize = 34f; setTextColor(white)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.16f; gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "PRIVATE CONNECTION. YOUR CONTROL."
            textSize = 10f; letterSpacing = 0.18f; setTextColor(cyan); gravity = Gravity.CENTER
        }, matchWrap())
        addSpace(root, 30)

        statusView = TextView(this).apply {
            text = "DISCONNECTED"; textSize = 15f; letterSpacing = 0.08f
            setTextColor(red); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(statusView, matchWrap())
        addSpace(root, 6)
        statsView = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
        }
        root.addView(statsView, matchWrap())
        addSpace(root, 14)

        connectButton = Button(this).apply {
            text = "CONNECT"
            textSize = 17f
            setTextColor(Color.rgb(4, 10, 16))
            setBackgroundColor(cyan)
            setOnClickListener { onConnectClicked() }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(MATCH, dp(60)))
        addSpace(root, 18)

        selectedView = TextView(this).apply {
            textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
        }
        root.addView(selectedView, matchWrap())
        addSpace(root, 20)

        root.addView(TextView(this).apply {
            text = "SAVED PROFILES — tap to select, ✕ removes"
            textSize = 11f; letterSpacing = 0.1f; setTextColor(cyan); gravity = Gravity.CENTER
        }, matchWrap())
        addSpace(root, 8)
        profilesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(panel)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(profilesBox, matchWrap())
        addSpace(root, 16)

        val importRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val importFileBtn = Button(this).apply {
            text = "IMPORT .CONF FILE"
            setOnClickListener { pickConfigFile() }
        }
        val pasteBtn = Button(this).apply {
            text = "PASTE CONFIG"
            setOnClickListener { showPasteDialog() }
        }
        importRow.addView(importFileBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        addSpaceH(importRow, 10)
        importRow.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(importRow, matchWrap())
        addSpace(root, 18)

        root.addView(TextView(this).apply {
            text = "VYBEX is a real WireGuard client — no servers are bundled and " +
                "nothing is faked. Import a standard WireGuard .conf profile from your " +
                "own server or any VPN provider that offers WireGuard configs, then tap CONNECT."
            textSize = 12.5f; setTextColor(muted); gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, matchWrap())
        addSpace(root, 14)
        root.addView(TextView(this).apply {
            text = "WORKING ENGINE: WIREGUARD®  •  ANDROID EDITION"
            textSize = 10f; letterSpacing = 0.12f; setTextColor(muted); gravity = Gravity.CENTER
        }, matchWrap())

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
    }

    // ------------------------------------------------------------ profiles

    private fun profileNames(): List<String> =
        profilesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".conf") }
            ?.map { it.name.removeSuffix(".conf") }?.sorted() ?: emptyList()

    private fun selectedName(): String? {
        val saved = prefs.getString("selected", null)
        val names = profileNames()
        return when {
            saved != null && names.contains(saved) -> saved
            names.isNotEmpty() -> names.first()
            else -> null
        }
    }

    private fun refreshProfiles() {
        profilesBox.removeAllViews()
        val names = profileNames()
        val selected = selectedName()
        if (names.isEmpty()) {
            profilesBox.addView(TextView(this).apply {
                text = "No profiles yet — import a .conf to get started"
                textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }, matchWrap())
        }
        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val label = TextView(this).apply {
                text = (if (name == selected) "● " else "○ ") + name
                textSize = 15f
                setTextColor(if (name == selected) green else white)
                setOnClickListener {
                    prefs.edit().putString("selected", name).apply()
                    if (!connected) tunnel = null
                    refreshProfiles()
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            val del = TextView(this).apply {
                text = "✕"; textSize = 16f; setTextColor(red)
                setPadding(dp(12), dp(4), dp(8), dp(4))
                setOnClickListener { confirmDelete(name) }
            }
            row.addView(del, LinearLayout.LayoutParams(WRAP, WRAP))
            profilesBox.addView(row, matchWrap())
        }
        selectedView.text = when {
            connected -> "Tunnel profile: ${tunnel?.name ?: selected ?: "—"}"
            selected != null -> "Selected profile: $selected"
            else -> "No profile selected"
        }
    }

    private fun confirmDelete(name: String) {
        if (connected && tunnel?.name == name) {
            toast("Disconnect before deleting the active profile")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage("Remove \"$name\" from this device?")
            .setPositiveButton("Delete") { _, _ ->
                File(profilesDir, "$name.conf").delete()
                if (prefs.getString("selected", null) == name) {
                    prefs.edit().remove("selected").apply()
                }
                if (tunnel?.name == name) tunnel = null
                refreshProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveProfile(rawName: String, text: String) {
        val name = rawName.trim().replace(Regex("[^A-Za-z0-9_=+.-]"), "_")
            .take(32).ifEmpty { "profile" }
        try {
            // Validate with the real WireGuard parser before saving.
            Config.parse(text.byteInputStream().bufferedReader())
        } catch (e: Exception) {
            toast("That is not a valid WireGuard config: ${e.message}")
            return
        }
        File(profilesDir, "$name.conf").writeText(text)
        prefs.edit().putString("selected", name).apply()
        if (!connected) tunnel = null
        refreshProfiles()
        toast("Profile \"$name\" saved")
    }

    // -------------------------------------------------------------- import

    private fun pickConfigFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_FILE)
    }

    private fun showPasteDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        val nameInput = EditText(this).apply { hint = "Profile name (e.g. my-server)" }
        val textInput = EditText(this).apply {
            hint = "Paste the full WireGuard config text ([Interface] … [Peer] …)"
            minLines = 8; gravity = Gravity.TOP or Gravity.START
        }
        box.addView(nameInput)
        box.addView(textInput)
        AlertDialog.Builder(this)
            .setTitle("Paste WireGuard config")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = nameInput.text.toString()
                saveProfile(if (n.isBlank()) "pasted-config" else n, textInput.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- tunnel

    private fun currentTunnel(): WgTunnel {
        val name = selectedName() ?: throw IllegalStateException("No profile selected")
        val existing = tunnel
        if (existing != null && existing.name == name) return existing
        val created = WgTunnel(name)
        tunnel = created
        return created
    }

    private fun onConnectClicked() {
        if (busy) return
        if (connected) {
            setTunnelState(up = false)
            return
        }
        if (selectedName() == null) {
            toast("Import a WireGuard .conf profile first")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingConnect = true
            startActivityForResult(prepareIntent, REQ_VPN_PERMISSION)
        } else {
            setTunnelState(up = true)
        }
    }

    private fun setTunnelState(up: Boolean) {
        busy = true
        updateStatusUi()
        executor.execute {
            try {
                val t = currentTunnel()
                if (up) {
                    val file = File(profilesDir, "${t.name}.conf")
                    val config = Config.parse(file.inputStream().bufferedReader())
                    backend.setState(t, Tunnel.State.UP, config)
                } else {
                    backend.setState(t, Tunnel.State.DOWN, null)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    busy = false
                    toast("Tunnel error: ${e.message}")
                    syncTunnelState()
                }
            }
        }
    }

    private fun onTunnelState(state: Tunnel.State) {
        busy = false
        connected = state == Tunnel.State.UP
        updateStatusUi()
        refreshProfiles()
        if (connected) startStats() else stopStats()
    }

    private fun syncTunnelState() {
        executor.execute {
            val state = try {
                val name = selectedName()
                if (name == null) Tunnel.State.DOWN else backend.getState(currentTunnel())
            } catch (e: Exception) {
                Tunnel.State.DOWN
            }
            mainHandler.post { onTunnelState(state) }
        }
    }

    private fun updateStatusUi() {
        when {
            busy && !connected -> {
                statusView.text = "CONNECTING…"; statusView.setTextColor(cyan)
                connectButton.text = "WORKING…"; connectButton.isEnabled = false
            }
            busy && connected -> {
                statusView.text = "DISCONNECTING…"; statusView.setTextColor(cyan)
                connectButton.text = "WORKING…"; connectButton.isEnabled = false
            }
            connected -> {
                statusView.text = "● CONNECTED — TRAFFIC ENCRYPTED"
                statusView.setTextColor(green)
                connectButton.text = "DISCONNECT"; connectButton.isEnabled = true
                connectButton.setBackgroundColor(red)
            }
            else -> {
                statusView.text = "DISCONNECTED"; statusView.setTextColor(red)
                statsView.text = ""
                connectButton.text = "CONNECT"; connectButton.isEnabled = true
                connectButton.setBackgroundColor(cyan)
            }
        }
        if (connected) connectButton.setTextColor(white) else connectButton.setTextColor(Color.rgb(4, 10, 16))
    }

    // --------------------------------------------------------------- stats

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            executor.execute {
                try {
                    val t = tunnel ?: return@execute
                    val stats = backend.getStatistics(t)
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    mainHandler.post {
                        if (connected) statsView.text = "↓ ${formatBytes(rx)} received   •   ↑ ${formatBytes(tx)} sent"
                    }
                } catch (e: Exception) {
                    // Stats are best-effort; the tunnel itself is unaffected.
                }
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    private fun startStats() {
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 500)
    }

    private fun stopStats() {
        mainHandler.removeCallbacks(statsRunnable)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    // ------------------------------------------------------------ results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN_PERMISSION -> {
                if (resultCode == RESULT_OK && pendingConnect) {
                    pendingConnect = false
                    setTunnelState(up = true)
                } else {
                    pendingConnect = false
                    toast("VPN permission is required to connect")
                }
            }
            REQ_FILE -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data ?: return
                    try {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        if (text.isNullOrBlank()) {
                            toast("That file is empty")
                            return
                        }
                        val base = uri.lastPathSegment
                            ?.substringAfterLast('/')?.removeSuffix(".conf")
                            ?.removeSuffix(".txt") ?: "imported"
                        saveProfile(base.ifBlank { "imported" }, text)
                    } catch (e: Exception) {
                        toast("Could not read that file: ${e.message}")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)
    private fun addSpace(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun addSpaceH(parent: LinearLayout, width: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(dp(width), 1))
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQ_VPN_PERMISSION = 1001
        private const val REQ_FILE = 1002
    }
}
