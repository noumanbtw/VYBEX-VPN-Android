package com.vybex.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
 * VYBEX VPN — working WireGuard client with the purple design skin.
 *
 * Real tunnel via the official WireGuard embeddable tunnel library
 * (com.wireguard.android:tunnel, GoBackend). No servers are bundled and
 * nothing is faked: the user imports standard WireGuard .conf profiles
 * (their own server or any provider); each profile is a "location".
 * Speeds shown are measured live from the tunnel's own byte counters.
 */
class MainActivity : Activity() {

    // ------------------------------------------------------------- palette
    private val bg = Color.rgb(13, 6, 24)
    private val panel = Color.rgb(28, 15, 51)
    private val panelSoft = Color.rgb(35, 19, 63)
    private val purple = Color.rgb(139, 70, 232)
    private val purpleDeep = Color.rgb(109, 40, 217)
    private val violetText = Color.rgb(196, 141, 255)
    private val green = Color.rgb(52, 224, 140)
    private val red = Color.rgb(255, 99, 99)
    private val muted = Color.rgb(167, 152, 196)
    private val faint = Color.rgb(120, 104, 152)
    private val white = Color.WHITE

    // -------------------------------------------------------------- engine
    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tunnel: WgTunnel? = null
    private var connected = false
    private var busy = false
    private var pendingConnect = false

    // ----------------------------------------------------------------- ui
    private lateinit var host: FrameLayout
    private lateinit var welcomeScreen: View
    private lateinit var homeScreen: View
    private lateinit var locationScreen: View

    private lateinit var statusView: TextView
    private lateinit var totalsView: TextView
    private lateinit var downRateView: TextView
    private lateinit var upRateView: TextView
    private lateinit var powerButton: PowerButtonView
    private lateinit var locationCardName: TextView
    private lateinit var profilesBox: LinearLayout
    private lateinit var searchInput: EditText

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastStatsAt = 0L

    private val profilesDir: File get() = File(filesDir, "profiles").apply { mkdirs() }
    private val prefs by lazy { getSharedPreferences("vybex", MODE_PRIVATE) }

    inner class WgTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            mainHandler.post { onTunnelState(newState) }
        }
    }

    /** Concentric-circle logo art for the welcome screen. */
    inner class CirclesView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = purple
        }
        private val ringSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f
            color = Color.argb(110, 139, 70, 232)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = panelSoft
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val maxR = minOf(width, height) / 2f - 6f
            canvas.drawCircle(cx, cy, maxR, ringSoft)
            canvas.drawCircle(cx, cy, maxR * 0.76f, ringSoft)
            canvas.drawCircle(cx, cy, maxR * 0.52f, ring)
            canvas.drawCircle(cx, cy, maxR * 0.30f, fill)
            canvas.drawCircle(cx, cy, maxR * 0.30f, ring)
        }
    }

    /** Big round power button: ring + power glyph, purple glow when ON. */
    inner class PowerButtonView(context: Context) : View(context) {
        var on: Boolean = false
            set(value) { field = value; invalidate() }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND; color = white
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f - 10f
            fillPaint.color = if (on) purpleDeep else panel
            ringPaint.color = if (on) purple else Color.rgb(74, 52, 110)
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
            // Power glyph: 300-degree arc + stem
            val gr = r * 0.42f
            val rect = RectF(cx - gr, cy - gr, cx + gr, cy + gr)
            glyphPaint.color = if (on) white else violetText
            canvas.drawArc(rect, -60f, 300f, false, glyphPaint)
            canvas.drawLine(cx, cy - gr * 1.25f, cx, cy + gr * 0.15f, glyphPaint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        backend = GoBackend(applicationContext)

        host = FrameLayout(this).apply { setBackgroundColor(bg) }
        welcomeScreen = buildWelcome()
        homeScreen = buildHome()
        locationScreen = buildLocations()
        host.addView(homeScreen)
        host.addView(locationScreen)
        host.addView(welcomeScreen)
        locationScreen.visibility = View.GONE
        homeScreen.visibility = View.GONE
        welcomeScreen.visibility = View.VISIBLE
        setContentView(host)

        refreshProfiles()
        syncTunnelState()
    }

    private fun show(screen: View) {
        welcomeScreen.visibility = View.GONE
        homeScreen.visibility = View.GONE
        locationScreen.visibility = View.GONE
        screen.visibility = View.VISIBLE
    }

    // ------------------------------------------------------- welcome screen

    private fun buildWelcome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(32))
            setBackgroundColor(bg)
        }
        addSpace(root, 10)
        val art = FrameLayout(this)
        art.addView(CirclesView(this), FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        art.addView(TextView(this).apply {
            text = "V"; textSize = 54f; setTextColor(white)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))
        root.addView(art, LinearLayout.LayoutParams(MATCH, dp(250)))
        addSpace(root, 8)
        root.addView(TextView(this).apply {
            text = "VPN"; textSize = 13f; letterSpacing = 0.5f
            setTextColor(violetText); gravity = Gravity.CENTER
        }, matchWrap())
        addSpace(root, 30)
        root.addView(TextView(this).apply {
            text = "Explore the World\nWide Web Safely."
            textSize = 25f; setTextColor(white); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
        }, matchWrap())
        addSpace(root, 10)
        root.addView(TextView(this).apply {
            text = "Secure. Fast. Stable"
            textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
        }, matchWrap())
        addSpace(root, 44)
        val tryBtn = Button(this).apply {
            text = "Try Now"; textSize = 16f; setTextColor(white)
            background = roundedGradient(dp(28))
            setOnClickListener { show(homeScreen) }
        }
        root.addView(tryBtn, LinearLayout.LayoutParams(MATCH, dp(58)))
        addSpace(root, 14)
        root.addView(TextView(this).apply {
            text = "Real WireGuard® engine — bring your own server profile"
            textSize = 11f; setTextColor(faint); gravity = Gravity.CENTER
        }, matchWrap())
        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    // ---------------------------------------------------------- home screen

    private fun buildHome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(white)
            setPadding(dp(2), 0, dp(10), dp(4))
            setOnClickListener { show(welcomeScreen) }
        }, wrapWrap())
        header.addView(TextView(this).apply {
            text = "Connection"; textSize = 18f; setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(TextView(this).apply {
            text = "V"; textSize = 16f; setTextColor(violetText)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(dp(10), 0, dp(2), 0)
        }, wrapWrap())
        root.addView(header, matchWrap())
        addSpace(root, 22)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        downRateView = chip("↓ Download", "—")
        upRateView = chip("↑ Upload", "—")
        chips.addView(downRateView.parent as View, LinearLayout.LayoutParams(0, dp(56), 1f))
        addSpaceH(chips, 12)
        chips.addView(upRateView.parent as View, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(chips, matchWrap())
        addSpace(root, 30)

        statusView = TextView(this).apply {
            text = "NOT CONNECTED"; textSize = 17f; letterSpacing = 0.14f
            setTextColor(white); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(statusView, matchWrap())
        addSpace(root, 18)

        powerButton = PowerButtonView(this).apply {
            setOnClickListener { onConnectClicked() }
        }
        root.addView(powerButton, LinearLayout.LayoutParams(dp(168), dp(168)))
        addSpace(root, 14)
        totalsView = TextView(this).apply {
            text = "Tap the power button to connect"
            textSize = 12.5f; setTextColor(muted); gravity = Gravity.CENTER
        }
        root.addView(totalsView, matchWrap())
        addSpace(root, 26)

        // Selected location card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedPanel(dp(18))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { show(locationScreen); refreshProfiles() }
        }
        card.addView(TextView(this).apply {
            text = "◎"; textSize = 22f; setTextColor(green)
        }, wrapWrap())
        val cardTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        locationCardName = TextView(this).apply {
            text = "No profile yet"; textSize = 15f; setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
        }
        cardTexts.addView(locationCardName)
        cardTexts.addView(TextView(this).apply {
            text = "Tap to choose a location / profile"
            textSize = 11.5f; setTextColor(muted)
        })
        card.addView(cardTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(muted)
        }, wrapWrap())
        root.addView(card, matchWrap())
        addSpace(root, 26)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = roundedPanel(dp(20))
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        nav.addView(navItem("◎", "Locations") { show(locationScreen); refreshProfiles() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(navItem("＋", "Import") { pickConfigFile() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(navItem("▤", "Paste") { showPasteDialog() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(nav, matchWrap())

        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    private fun chip(label: String, value: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = roundedPanel(dp(16))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        box.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER
        })
        val valueView = TextView(this).apply {
            text = value; textSize = 15f; setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        box.addView(valueView)
        // Callers update the returned value TextView; its parent is the chip box.
        return valueView
    }

    private fun navItem(glyph: String, label: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { action() }
        }
        box.addView(TextView(this).apply {
            text = glyph; textSize = 20f; setTextColor(violetText); gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER
        })
        return box
    }

    // ------------------------------------------------------ location screen

    private fun buildLocations(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(white)
            setPadding(dp(2), 0, dp(10), dp(4))
            setOnClickListener { show(homeScreen) }
        }, wrapWrap())
        header.addView(TextView(this).apply {
            text = "Location"; textSize = 18f; setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(TextView(this).apply {
            text = "V"; textSize = 16f; setTextColor(violetText)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(dp(10), 0, dp(2), 0)
        }, wrapWrap())
        root.addView(header, matchWrap())
        addSpace(root, 18)

        searchInput = EditText(this).apply {
            hint = "Search Location"
            setHintTextColor(faint); setTextColor(white); textSize = 14f
            background = roundedPanel(dp(16))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshProfiles() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(MATCH, dp(52)))
        addSpace(root, 20)
        root.addView(TextView(this).apply {
            text = "Your Profiles"; textSize = 13f; setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        addSpace(root, 6)
        root.addView(TextView(this).apply {
            text = "Each profile is one real WireGuard server config (.conf). " +
                "Import more below — no fake locations, ever."
            textSize = 11.5f; setTextColor(faint)
        }, matchWrap())
        addSpace(root, 12)

        profilesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(profilesBox, matchWrap())
        addSpace(root, 18)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val importBtn = Button(this).apply {
            text = "IMPORT .CONF"; setTextColor(white); textSize = 13f
            background = roundedGradient(dp(16))
            setOnClickListener { pickConfigFile() }
        }
        val pasteBtn = Button(this).apply {
            text = "PASTE CONFIG"; setTextColor(white); textSize = 13f
            background = roundedPanel(dp(16))
            setOnClickListener { showPasteDialog() }
        }
        row.addView(importBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        addSpaceH(row, 10)
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(row, matchWrap())

        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
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
        if (!::profilesBox.isInitialized) return
        profilesBox.removeAllViews()
        val filter = if (::searchInput.isInitialized) searchInput.text?.toString()?.trim()?.lowercase() else null
        val names = profileNames().filter { filter.isNullOrEmpty() || it.lowercase().contains(filter) }
        val selected = selectedName()
        if (names.isEmpty()) {
            profilesBox.addView(TextView(this).apply {
                text = if (profileNames().isEmpty())
                    "No profiles yet — import a .conf to add your first location."
                else "No profile matches your search."
                textSize = 13f; setTextColor(muted)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }, matchWrap())
        }
        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedPanel(dp(16))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    prefs.edit().putString("selected", name).apply()
                    if (!connected) tunnel = null
                    refreshProfiles()
                    show(homeScreen)
                }
            }
            row.addView(TextView(this).apply {
                text = initials(name); textSize = 13f; setTextColor(white)
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = circleBadge()
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = name; textSize = 15f
                setTextColor(if (name == selected) white else Color.rgb(220, 210, 240))
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (name == selected) {
                row.addView(TextView(this).apply {
                    text = "●"; textSize = 14f; setTextColor(purple)
                    setPadding(dp(6), 0, dp(6), 0)
                }, wrapWrap())
            }
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 15f; setTextColor(red)
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener { confirmDelete(name) }
            }, wrapWrap())
            profilesBox.addView(row, matchWrap())
            addSpace(profilesBox, 10)
        }
        if (::locationCardName.isInitialized) {
            locationCardName.text = when {
                connected -> "${tunnel?.name ?: selected ?: "—"} — connected"
                selected != null -> selected
                else -> "No profile yet"
            }
        }
    }

    private fun initials(name: String): String {
        val parts = name.replace('_', ' ').replace('-', ' ').split(' ')
            .filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> (parts[0].take(1) + parts[1].take(1)).uppercase()
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "VY"
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
        val nameInput = EditText(this).apply { hint = "Profile name (e.g. Pakistan - Lahore)" }
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
            show(locationScreen)
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
        if (!connected) { lastRx = -1; lastTx = -1 }
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
        if (!::statusView.isInitialized) return
        powerButton.on = connected
        when {
            busy && !connected -> {
                statusView.text = "CONNECTING…"
                totalsView.text = "Setting up the encrypted tunnel…"
            }
            busy && connected -> {
                statusView.text = "DISCONNECTING…"
                totalsView.text = "Closing the tunnel…"
            }
            connected -> {
                statusView.text = "CONNECTED"
                totalsView.text = "Your traffic is encrypted"
            }
            else -> {
                statusView.text = "NOT CONNECTED"
                totalsView.text = "Tap the power button to connect"
                downRateView.text = "—"
                upRateView.text = "—"
            }
        }
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
                    val now = System.currentTimeMillis()
                    mainHandler.post {
                        if (!connected) return@post
                        if (lastRx >= 0 && lastStatsAt > 0) {
                            val dt = (now - lastStatsAt) / 1000.0
                            if (dt > 0) {
                                downRateView.text = formatRate(((rx - lastRx) / dt).toLong())
                                upRateView.text = formatRate(((tx - lastTx) / dt).toLong())
                            }
                        }
                        totalsView.text = "↓ ${formatBytes(rx)} received   •   ↑ ${formatBytes(tx)} sent"
                        lastRx = rx; lastTx = tx; lastStatsAt = now
                    }
                } catch (e: Exception) {
                    // Stats are best-effort; the tunnel itself is unaffected.
                }
            }
            mainHandler.postDelayed(this, 1500)
        }
    }

    private fun startStats() {
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 400)
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

    private fun formatRate(bytesPerSec: Long): String = when {
        bytesPerSec < 0 -> "—"
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024))
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

    // ------------------------------------------------------------- styling

    private fun roundedPanel(radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(panel)
        }

    private fun roundedGradient(radius: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(purple, purpleDeep)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
        }

    private fun circleBadge(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(purpleDeep)
        }

    // -------------------------------------------------------------- helpers

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)
    private fun wrapWrap() = LinearLayout.LayoutParams(WRAP, WRAP)
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
