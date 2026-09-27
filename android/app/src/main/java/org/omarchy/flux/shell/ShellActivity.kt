package org.omarchy.flux.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.text.util.LocalePreferences
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.omarchy.flux.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.UUID

/**
 * The phone host for the OmarchyRemote web shell: a full-screen WebView on a computer's
 * `/native/` page, with a device bridge for the parts of the shell that must be the phone.
 *
 * The bridge is the whole contract with the web code. Pages on other origins, including the
 * websites a browser app embeds, never receive it, so an embedded page cannot ask the phone
 * for a file, a key, or a position. See docs/shell.md.
 */
class ShellActivity : Activity() {
    private val pages = LinkedHashMap<String, ShellPage>()
    private val deviceKeys = ShellKeys()
    private val consumedKeys = mutableSetOf<Int>()
    private val launchKeys = mutableListOf<KeyEvent>()
    private val handler = Handler(Looper.getMainLooper())
    private val launchAbandon = Runnable { cancelLaunchFocus() }
    private val reconnect = Runnable { probeHost() }

    internal lateinit var root: FrameLayout
    private lateinit var hosts: ShellHosts
    private lateinit var location: ShellLocation
    private lateinit var files: ShellFiles
    private lateinit var prefs: SharedPreferences
    private lateinit var assetLoader: WebViewAssetLoader
    private var shell: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var shellEditing = false
    private var bundled = false
    private var foreground = false
    private var probing = false
    private var retrySeconds = 3
    private var awaitingLaunchFocus = false
    private var replayingLaunchKeys = false
    private var launchRequestedToken = 0
    private var launchReadyToken = 0
    private var launchGeneration = 0
    private var backEdge = -1
    private var hasHardwareKeyboard = false

    /** Remembered for new pages, so every browser app opens in the same mode. */
    var forceDark: Boolean = false
        private set

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            publishBattery(if (level < 0) -1 else Math.round(100f * level / scale), state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(resources.configuration)
        prefs = getSharedPreferences("shell", MODE_PRIVATE)
        forceDark = prefs.getBoolean(FORCE_DARK, false)
        hosts = ShellHosts(this)
        hosts.sync(Settings(this).shellUrl)
        location = ShellLocation(this)
        files = ShellFiles(this)
        hasHardwareKeyboard = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY

        root = FrameLayout(this).apply { setBackgroundColor(COLOR) }
        setContentView(root)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            reserveEdgeSwipes(right - left, bottom - top)
        }
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        goFullScreen()
        registerBack()
        root.setOnApplyWindowInsetsListener { _, insets ->
            publishKeyboard(imeInset(insets))
            insets
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        loadShell(hosts.disconnected || hosts.selected.isEmpty())
    }

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- the shell page ----------

    // A web shell runs JavaScript by definition; nothing else is loaded here.
    @SuppressLint("SetJavaScriptEnabled")
    internal fun makeWebView(): WebView {
        val web = object : WebView(this) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                val editing = shellEditing || pages.values.any { it.web.hasFocus() }
                if (keyCode in consumedKeys ||
                    (event.action == KeyEvent.ACTION_DOWN && deviceKeys.match(event, editing) != null)
                ) {
                    return dispatchKeyEvent(event)
                }
                if (event.unicodeChar >= 32 && bufferTransitionKey(event)) return true
                if (keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed &&
                    !event.isAltPressed && !event.isMetaPressed
                ) {
                    // Escape belongs to the active app, including terminal programs. Android
                    // Back remains the system keyboard-dismiss action.
                    super.dispatchKeyEvent(event)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_BACK && !imeVisible()) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goBack()
                    return true
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }
        web.setBackgroundColor(COLOR)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES)
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                try {
                    startActivityForResult(params.createIntent(), CHOOSER_REQUEST)
                } catch (_: ActivityNotFoundException) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                }
                return true
            }

            // target="_blank" without a popup: load the page in this webview.
            override fun onCreateWindow(
                view: WebView,
                dialog: Boolean,
                userGesture: Boolean,
                result: android.os.Message,
            ): Boolean {
                if (!userGesture) return false
                val temporary = WebView(this@ShellActivity)
                temporary.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (isHttp(request.url)) view.loadUrl(request.url.toString())
                        temporary.destroy()
                        return true
                    }
                }
                (result.obj as WebView.WebViewTransport).webView = temporary
                result.sendToTarget()
                return true
            }
        }
        return web
    }

    // Guarded above by WebViewFeature: without both features the shell gets a dialog instead.
    @SuppressLint("RequiresFeature")
    private fun loadShell(picker: Boolean) {
        handler.removeCallbacks(reconnect)
        cancelLaunchFocus()
        bundled = false
        retrySeconds = 3
        deviceKeys.register(JSONArray())
        consumedKeys.clear()
        shellEditing = false
        location.cancel()
        files.cancel()
        for (page in pages.values) page.destroy()
        pages.clear()
        shell?.let {
            root.removeView(it)
            it.destroy()
        }
        val web = makeWebView()
        web.settings.setNeedInitialFocus(false)
        web.setOnTouchListener { view, event ->
            // Swipes can focus a DOM editor without giving its webview native focus. Acquire
            // it before the gesture, keeping the editor the shell chose.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.requestFocus()
            false
        }
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        shell = web
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            AlertDialog.Builder(this)
                .setMessage("Update Android System WebView to run the desktop shell.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val selected = hosts.selected
        val allowed = mutableSetOf(ASSET)
        if (selected.isNotEmpty()) allowed.add(origin(selected))
        var deviceId = prefs.getString("device-id", "")
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device-id", deviceId).apply()
        }
        val device = json(
            "id" to deviceId,
            "name" to Build.MODEL,
            "scope" to selected,
            "hosts" to hosts.directory,
            "snapshot" to jsonOf(prefs.getString("state:$selected", "{}")),
        )
        val source = "window.__OMARCHY_DEVICE__=$device;" + assetText("android-bridge.js")
        WebViewCompat.addDocumentStartJavaScript(web, source, allowed)
        WebViewCompat.addWebMessageListener(
            web,
            "AndroidShell",
            allowed,
        ) { _, message, sourceOrigin, isMainFrame, reply ->
            // Only the shell's own document may drive the device.
            if (!isMainFrame) return@addWebMessageListener
            val scope = hosts.selected
            val trusted = sourceOrigin.toString() == ASSET ||
                (scope.isNotEmpty() && origin(scope) == sourceOrigin.toString())
            if (!trusted) return@addWebMessageListener
            val request = jsonOf(message.data)
            val serial = request.optInt("id")
            try {
                dispatch(
                    request.optString("channel"),
                    request.opt("body"),
                ) { value -> reply.postMessage(json("id" to serial, "value" to value).toString()) }
            } catch (error: Exception) {
                reply.postMessage(
                    json(
                        "id" to serial,
                        "error" to (error.message ?: "Native request failed"),
                    ).toString(),
                )
            }
        }
        val bundledAssets = WebViewAssetLoader.AssetsPathHandler(this)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                files.cancel()
                location.cancel()
            }

            // While bundled, the host's own /native/ paths come from the copy in assets, so
            // the shell keeps working with the computer switched off.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                val path = url.path
                if (bundled && hosts.selected.isNotEmpty() &&
                    origin(url.toString()) == origin(hosts.selected) &&
                    path != null && path.startsWith("/native/")
                ) {
                    val rest = path.removePrefix("/native/")
                    return bundledAssets.handle("Web/" + if (rest.isEmpty()) "index.html" else rest)
                }
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url
                if (allowed.contains(origin(url.toString()))) return false
                if (request.hasGesture() && isHttp(url)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== shell) return
                evaluate(pageState())
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) fallback(view)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (request.isForMainFrame) fallback(view)
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel()
                fallback(view)
            }
        }
        web.loadUrl(if (picker) "$ASSET/assets/Web/hosts.html" else hosts.selected)
    }

    private fun fallback(view: WebView) {
        if (view !== shell || bundled || hosts.selected.isEmpty() || hosts.disconnected) return
        bundled = true
        view.loadUrl(hosts.selected)
        handler.removeCallbacks(reconnect)
        if (foreground) handler.postDelayed(reconnect, retrySeconds * 1000L)
    }

    /** Retry the host until it answers again, so the shell leaves the bundled copy by itself. */
    private fun probeHost() {
        if (!foreground || !bundled || probing || hosts.selected.isEmpty()) return
        probing = true
        val host = hosts.selected
        val target = shell
        Thread({
            var connection: HttpURLConnection? = null
            val ready = try {
                connection = URL(host).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                connection.responseCode == 200 &&
                    connection.contentType.orEmpty().startsWith("text/html")
            } catch (_: Exception) {
                // Stay in the usable bundled shell until this exact host answers.
                false
            } finally {
                connection?.disconnect()
            }
            handler.post {
                probing = false
                if (!foreground || !bundled) return@post
                if (target === shell && host == hosts.selected && ready) {
                    loadShell(false)
                } else {
                    retrySeconds = minOf(30, retrySeconds * 2)
                    handler.postDelayed(reconnect, retrySeconds * 1000L)
                }
            }
        }, "shell-host-reconnect").start()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        shell?.let {
            it.onResume()
            evaluate("window.dispatchEvent(new Event('online'));window.dispatchEvent(new Event('focus'));")
        }
        for (page in pages.values) page.web.onResume()
        handler.removeCallbacks(reconnect)
        if (bundled) handler.post(reconnect)
    }

    override fun onPause() {
        cancelLaunchFocus()
        foreground = false
        handler.removeCallbacks(reconnect)
        shell?.let {
            evaluate("window.dispatchEvent(new Event('pagehide'));")
            it.onPause()
        }
        for (page in pages.values) page.web.onPause()
        super.onPause()
    }

    // ---------- the device bridge ----------

    private fun dispatch(channel: String, raw: Any?, reply: (Any?) -> Unit) {
        val body = raw as? JSONObject ?: JSONObject()
        when (channel) {
            "shellStorage" -> {
                saveState(body)
                reply(true)
            }

            "shellHosts" -> hostNavigation(body, reply)
            "shellKeyboard" -> keyboard(raw, body, reply)
            "shellFiles" -> files.dispatch(body) { reply(it) }
            "weatherDevice" -> weather(body, reply)
            "browserDevice" -> reply(browser(body))
            else -> throw IllegalArgumentException("Unsupported bridge: $channel")
        }
    }

    private fun saveState(body: JSONObject) {
        val values = body.optJSONObject("values") ?: return
        val scope = hosts.selected
        if (scope.isNotEmpty() && body.optString("scope") == scope && values.toString().length <= 1048576) {
            prefs.edit().putString("state:$scope", values.toString()).apply()
        }
    }

    private fun keyboard(raw: Any?, body: JSONObject, reply: (Any?) -> Unit) {
        if (body.optBoolean("dismiss")) hideKeyboard()
        if (raw is Boolean) shellEditing = raw
        if (body.has("commands")) deviceKeys.register(body.optJSONArray("commands"))
        if (body.has("focusRequest")) focusFromShell(body)
        reply(true)
    }

    /** The shell asks for the keyboard to open on a terminal row or an app field. */
    private fun focusFromShell(body: JSONObject) {
        val token = body.optInt("focusRequest")
        val generation = launchGeneration
        if (awaitingLaunchFocus) launchRequestedToken = token
        evaluate("window.HyprlandRemote?.focusFromNative($token,false)") { focused ->
            if (focused != "true") return@evaluate
            shell?.requestFocus()
            evaluate("window.HyprlandRemote?.focusFromNative($token,true)") { confirmed ->
                if (confirmed != "true") return@evaluate
                if (generation == launchGeneration) {
                    launchReadyToken = token
                    drainLaunchKeys(token, generation)
                }
                if (body.optBoolean("keepKeyboardHidden")) hideKeyboard() else if (!hasHardwareKeyboard) {
                    showKeyboard()
                }
            }
        }
    }

    private fun weather(body: JSONObject, reply: (Any?) -> Unit) {
        when (body.optString("action")) {
            "locale" -> reply(
                json(
                    "unit" to if (LocalePreferences.TemperatureUnit.FAHRENHEIT ==
                        LocalePreferences.getTemperatureUnit()
                    ) {
                        "f"
                    } else {
                        "c"
                    },
                    "locale" to java.util.Locale.getDefault(java.util.Locale.Category.FORMAT).toLanguageTag(),
                ),
            )

            "location" -> location.request(body.optBoolean("requestPermission")) { found, error ->
                if (error != null) {
                    reply(json("error" to error))
                } else {
                    reply(
                        json(
                            "lat" to Math.round(found!!.latitude * 100) / 100.0,
                            "lon" to Math.round(found.longitude * 100) / 100.0,
                            "name" to "Current location",
                        ),
                    )
                }
            }

            else -> throw IllegalArgumentException("Unknown weather action")
        }
    }

    private fun hostNavigation(body: JSONObject, reply: (Any?) -> Unit) {
        // Host navigation can replace the shell before its debounced mirror is sent, so save
        // the departing host's snapshot before anything changes.
        saveState(body)
        val action = body.optString("action")
        if (action == "prompt") {
            promptForHost(reply)
            return
        }
        if (action == "save") hosts.save(body.optString("name"), body.optString("url"))
        if (action == "remove") hosts.remove(body.optString("id"))
        if (action == "connect") hosts.connect(body.optString("id"))
        if (action == "disconnect") hosts.disconnect()
        reply(hosts.directory)
        val navigate = action == "connect" || action == "disconnect" || action == "manage"
        if (navigate) handler.post { loadShell(action != "connect") }
    }

    private fun promptForHost(reply: (Any?) -> Unit) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        val name = EditText(this)
        name.hint = "Name (optional)"
        val address = EditText(this)
        address.hint = "https://host.example"
        address.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        form.addView(name)
        form.addView(address)
        AlertDialog.Builder(this)
            .setTitle("Add host")
            .setView(form)
            .setNegativeButton("Cancel") { _, _ -> reply(hosts.directory) }
            .setPositiveButton("Save") { _, _ ->
                try {
                    hosts.save(name.text.toString(), address.text.toString())
                } catch (error: Exception) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
                reply(hosts.directory)
            }
            .show()
    }

    private fun browser(body: JSONObject): JSONObject {
        val action = body.optString("action")
        val id = body.optString("appID", "browser")
        val page = pages[id]
        if (action == "capabilities") {
            return json(
                "embedded" to true,
                "webApps" to true,
                "darkMode" to true,
                "dark" to (page?.dark ?: forceDark),
                "nativeFind" to true,
                "shortcuts" to true,
            )
        }
        if (action == "open") {
            val url = Uri.parse(body.optString("url"))
            if (!isHttp(url) || url.host == null || url.userInfo != null) {
                throw IllegalArgumentException("Unsupported website URL")
            }
            val target = page ?: ShellPage(this, id).also { pages[id] = it }
            target.web.loadUrl(url.toString())
        } else if (page != null) {
            when (action) {
                "layout" -> page.layout(body)
                "back" -> if (page.web.canGoBack()) page.web.goBack()
                "forward" -> if (page.web.canGoForward()) page.web.goForward()
                "reload" -> page.web.reload()
                "stop" -> page.web.stopLoading()
                "focus" -> if (body.optBoolean("active", true)) {
                    page.web.requestFocus()
                } else {
                    focusShell()
                }
                "close" -> {
                    pages.remove(id)
                    page.destroy()
                }
                "zoom" -> page.setZoom(body.optDouble("value", 1.0))
                "dark" -> {
                    page.applyDark(body.optBoolean("enabled"))
                    forceDark = page.dark
                    prefs.edit().putBoolean(FORCE_DARK, page.dark).apply()
                    return json("dark" to page.dark)
                }
                "findOpen" -> page.openFind()
                "findNext" -> page.findNext(body.optBoolean("backwards"))
                "findClose" -> return json("closed" to page.closeFind())
                "snapshot" -> page.snapshot()
            }
        }
        return json("ok" to true)
    }

    internal fun pageDark(id: String) {
        val dark = pages[id]?.dark == true
        pages[id]?.web?.evaluateJavascript(
            if (dark) "DarkReader.enable({brightness:100,contrast:100})" else "window.DarkReader?.disable()",
            null,
        )
    }

    internal fun focusShell() {
        shell?.requestFocus()
    }

    /** The shell's own directory, for the host picker it ships in assets. */
    internal fun assetText(file: String): String = try {
        getAssets().open(file).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                bytes.write(buffer, 0, count)
            }
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    } catch (error: Exception) {
        throw IllegalStateException(error)
    }

    internal val hardwareKeyboard: Boolean
        get() = hasHardwareKeyboard

    internal fun evaluate(code: String, callback: ((String) -> Unit)? = null) {
        shell?.evaluateJavascript(code, callback ?: {})
    }

    private fun showKeyboard() {
        shell?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** The keyboard's height in pixels, or 0. `WindowInsets.Type` needs Android 11. */
    private fun imeInset(insets: android.view.WindowInsets? = root.rootWindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 else 0

    private fun imeVisible(): Boolean = imeInset() > 0

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun batteryScript(percent: Int, state: Int): String {
        val name = when (state) {
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            else -> "unplugged"
        }
        return "window.__OMARCHY_BATTERY__=" + json("percent" to percent, "state" to name) +
            ";window.dispatchEvent(new Event('hyprland-battery'));"
    }

    private fun publishBattery(percent: Int, state: Int) {
        evaluate(batteryScript(percent, state))
    }

    /** How much room the keyboard takes, which the shell needs to lay out above it. */
    private fun keyboardScript(inset: Int): String {
        val density = resources.displayMetrics.density
        return "window.__HYPRLAND_KEYBOARD__={inset:" + inset / density +
            ",height:" + root.height / density +
            "};window.dispatchEvent(new Event('hyprland-keyboard'));"
    }

    private fun publishKeyboard(inset: Int) {
        evaluate(keyboardScript(inset))
    }

    private fun hardwareKeyboardScript(): String =
        "window.__OMARCHY_HARDWARE_KEYBOARD__=$hasHardwareKeyboard;" +
            "window.dispatchEvent(new Event('hyprland-hardware-keyboard'));"

    private fun publishHardwareKeyboard() {
        evaluate(hardwareKeyboardScript())
    }

    /**
     * Everything a freshly loaded document needs, as one script. The shell reloads itself after
     * connecting, and separate evaluateJavascript calls racing that navigation get dropped, so
     * each page load gets a single call.
     */
    private fun pageState(): String {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val percent = if (level < 0 || scale <= 0) -1 else Math.round(100f * level / scale)
        return "window.__OMARCHY_BUNDLED__=$bundled;" + keyboardScript(imeInset()) +
            hardwareKeyboardScript() + if (percent >= 0) batteryScript(percent, status) else ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
        hasHardwareKeyboard = newConfig.keyboard == Configuration.KEYBOARD_QWERTY
        publishHardwareKeyboard()
    }

    // ---------- back, edges, and orientation ----------

    // Android 14 reports which edge a Back swipe starts from: the right edge moves to the next
    // workspace and the left edge, like the button, steps back inside the shell. Below 14 the
    // shell's own edge swipes keep the middle of each edge and Android's Back does the rest.
    private fun registerBack() {
        // The application opts into predictive back, so from Android 13 the key event stops
        // arriving and the dispatcher is the only route.
        if (Build.VERSION.SDK_INT < 33) return
        if (Build.VERSION.SDK_INT < 34) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { goBack() },
            )
            return
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : OnBackAnimationCallback {
                override fun onBackStarted(event: BackEvent) {
                    backEdge = event.swipeEdge
                }

                override fun onBackCancelled() {
                    backEdge = -1
                }

                override fun onBackInvoked() {
                    val edge = backEdge
                    backEdge = -1
                    if (edge == BackEvent.EDGE_RIGHT) evaluate("window.HyprlandDesk?.nativeNext()")
                    else goBack()
                }
            },
        )
    }

    private fun reserveEdgeSwipes(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= 34) return
        val band = minOf(height, Math.round(200 * resources.displayMetrics.density))
        val edge = Math.ceil(width * 0.08).toInt()
        val top = (height - band) / 2
        root.systemGestureExclusionRects =
            listOf(Rect(0, top, edge, top + band), Rect(width - edge, top, width, top + band))
    }

    // Only screens as wide as the desk layout (600dp on the shorter side) rotate; a phone
    // keeps the portrait shell.
    private fun applyOrientation(config: Configuration) {
        requestedOrientation =
            if (config.smallestScreenWidthDp < 600) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun goBack() {
        if (imeVisible()) {
            hideKeyboard()
            evaluate("window.HyprlandDesk?.nativeBack({keyboard:true})")
            return
        }
        evaluate("window.HyprlandDesk?.nativeBack()") { handled ->
            if (handled == "true" || !foreground) return@evaluate
            for (page in pages.values) {
                if (page.web.hasFocus() && page.web.visibility == View.VISIBLE && page.web.canGoBack()) {
                    page.web.goBack()
                    return@evaluate
                }
            }
            moveTaskToBack(true)
        }
    }

    // ---------- keys typed before an app takes focus ----------

    private fun cancelLaunchFocus() {
        handler.removeCallbacks(launchAbandon)
        awaitingLaunchFocus = false
        replayingLaunchKeys = false
        launchRequestedToken = 0
        launchReadyToken = 0
        launchGeneration++
        launchKeys.clear()
    }

    /** Replay what was typed while the app was still opening, so no keystroke is lost. */
    private fun drainLaunchKeys(token: Int, generation: Int) {
        if (!awaitingLaunchFocus || replayingLaunchKeys || generation != launchGeneration ||
            token == 0 || token != launchRequestedToken || token != launchReadyToken
        ) {
            return
        }
        if (launchKeys.isEmpty()) {
            cancelLaunchFocus()
            return
        }
        val batch = ArrayList(launchKeys)
        val keys = JSONArray()
        for (event in batch) {
            if (event.action != KeyEvent.ACTION_DOWN) continue
            val special = when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> "Enter"
                KeyEvent.KEYCODE_DEL -> "Backspace"
                KeyEvent.KEYCODE_TAB -> "Tab"
                else -> null
            }
            if (special != null) {
                keys.put(json("code" to special))
            } else {
                val character = event.unicodeChar
                if (Character.isValidCodePoint(character)) {
                    keys.put(json("text" to String(Character.toChars(character))))
                }
            }
        }
        launchKeys.clear()
        replayingLaunchKeys = true
        evaluate("window.HyprlandRemote?.replayInput($token,$keys)") { result ->
            if (generation != launchGeneration) return@evaluate
            replayingLaunchKeys = false
            if (result != "true") {
                if (token == launchRequestedToken) {
                    cancelLaunchFocus()
                    return@evaluate
                }
                launchKeys.addAll(0, batch)
            }
            drainLaunchKeys(launchReadyToken, generation)
        }
    }

    private fun bufferTransitionKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (!awaitingLaunchFocus || key in consumedKeys || event.isCtrlPressed ||
            event.isAltPressed || event.isMetaPressed
        ) {
            return false
        }
        if (event.unicodeChar < 32 && key != KeyEvent.KEYCODE_ENTER &&
            key != KeyEvent.KEYCODE_DEL && key != KeyEvent.KEYCODE_TAB
        ) {
            return false
        }
        if (launchKeys.size < 256) launchKeys.add(KeyEvent(event))
        return true
    }

    // The activity opts into predictive back at the application level and registers an
    // OnBackAnimationCallback above Android 14. Below that, the Back key is the only signal.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goBack()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(key)) return true
        val web = shell ?: return super.dispatchKeyEvent(event)
        if (bufferTransitionKey(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (event.repeatCount > 0 && key in consumedKeys) return true
        val pageFocused = pages.values.any { it.web.hasFocus() && it.web.visibility == View.VISIBLE }
        val action = deviceKeys.match(event, shellEditing || pageFocused) ?: return super.dispatchKeyEvent(event)
        consumedKeys.add(key)
        cancelLaunchFocus()
        if (action.optBoolean("focusShell")) web.requestFocus()
        val windowTransition = !pageFocused && action.optString("owner") == "shell" &&
            (action.optString("group") == "Windows" || action.optString("group") == "Workspaces")
        if ((action.optBoolean("focusShell") && action.optString("group") == "Apps") || windowTransition) {
            awaitingLaunchFocus = true
            handler.postDelayed(launchAbandon, 2000)
        }
        // Send the registry chord, including the canonical Ctrl+Alt form of a Meta shortcut.
        evaluate(
            "window.HyprlandDesk?.nativeKey($action);" +
                if (awaitingLaunchFocus) "window.HyprlandRemote?.requestInputFocus();" else "",
        )
        return true
    }

    // ---------- results and teardown ----------

    @Suppress("DEPRECATION")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == ShellFiles.SAVE_REQUEST) files.result(result, data)
        if (request == CHOOSER_REQUEST) {
            val callback = fileCallback
            fileCallback = null
            // A multi-selection arrives as ClipData, which the default parser does not read.
            val clip = data?.clipData
            if (result == RESULT_OK && clip != null) {
                val selected = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                callback?.onReceiveValue(if (selected.isEmpty()) null else selected.toTypedArray())
            } else {
                callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data))
            }
        }
    }

    override fun onRequestPermissionsResult(
        request: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(request, permissions, grantResults)
        if (request == ShellLocation.PERMISSION_REQUEST) location.permissionResult()
    }

    override fun onDestroy() {
        foreground = false
        handler.removeCallbacks(reconnect)
        location.cancel()
        files.cancel()
        runCatching { unregisterReceiver(battery) }
        fileCallback?.onReceiveValue(null)
        for (page in pages.values) page.destroy()
        pages.clear()
        shell?.destroy()
        shell = null
        super.onDestroy()
    }

    companion object {
        /** The bundled copy of the shell, which is also the only origin trusted offline. */
        const val ASSET = "https://appassets.androidplatform.net"
        private const val FORCE_DARK = "browser-force-dark"
        private const val CHOOSER_REQUEST = 10
        private val COLOR = Color.rgb(25, 23, 36)
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}

/** Only real web URLs reach a webview; everything else is left to Android. */
internal fun isHttp(url: Uri) = url.scheme == "https" || url.scheme == "http"
