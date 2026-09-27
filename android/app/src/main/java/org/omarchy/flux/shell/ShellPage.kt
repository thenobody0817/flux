package org.omarchy.flux.shell

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * One webpage the shell embeds for its browser and web apps. The shell owns placement and
 * tells the page where to sit with `browserDevice`/`layout`, so the page never decides its own
 * geometry and never learns what is behind it.
 */
class ShellPage(private val host: ShellActivity, val id: String) {
    val web: WebView
    var dark = false
    private var loading = false
    private var hiddenControls = false
    private var roundedTop = false
    private var requestedFocus = false
    private var lastHoverAt = 0L
    private var findDialog: AlertDialog? = null
    private var findQuery = ""
    private var radius = 0f
    var zoom = 1.0
        private set

    init {
        dark = host.forceDark
        web = host.makeWebView()
        // Unstyled websites use a white canvas behind their default black text.
        web.setBackgroundColor(Color.WHITE)
        // Focusing the window must preserve the page's caret, not select its first field.
        web.settings.setNeedInitialFocus(false)
        web.visibility = View.GONE
        web.clipToOutline = true
        web.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Extend the top arc above the viewport while browser chrome is visible.
                outline.setRoundRect(
                    0,
                    if (roundedTop) 0 else -Math.ceil(radius.toDouble()).toInt(),
                    view.width,
                    view.height,
                    radius,
                )
            }
        }
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) emit(json("focused" to true))
            false
        }
        web.setOnHoverListener { _, event ->
            val action = event.actionMasked
            if ((action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE &&
                event.buttonState == 0 && !requestedFocus
            ) {
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_HOVER_ENTER || now - lastHoverAt >= 100) {
                    lastHoverAt = now
                    // The shell owns the opt-in preference and overlay/focus guards.
                    emit(json("hovered" to true))
                }
            }
            // Websites still receive their normal pointer and CSS hover events.
            false
        }
        web.setOnScrollChangeListener { _, _, y, _, _ ->
            val next = if (hiddenControls) y > 0 else y > 12
            if (!id.startsWith("webapp-") && next != hiddenControls) {
                hiddenControls = next
                emit(json("controlsHidden" to next))
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                publish()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                publish()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                applyZoom()
                publish()
                if (dark) {
                    view.evaluateJavascript(host.assetText("Web/vendor/darkreader/darkreader.js")) {
                        host.pageDark(id)
                    }
                }
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isHttp(request.url)
        }
        host.root.addView(web, android.widget.FrameLayout.LayoutParams(1, 1))
    }

    /** CSS zoom scales layout and images as well as text, below the page's pinch-zoom minimum. */
    fun applyZoom() {
        web.evaluateJavascript(
            "(() => {"
                + "const root=document.documentElement;if(!root)return;"
                + "let saved=window.__omarchyPageZoom;"
                + "if(!saved || saved.root!==root){"
                + "saved={root,value:root.style.getPropertyValue('zoom'),"
                + "priority:root.style.getPropertyPriority('zoom'),"
                + "base:parseFloat(getComputedStyle(root).zoom)||1};"
                + "window.__omarchyPageZoom=saved;}"
                + "const factor=$zoom;"
                + "if(factor===1){"
                + "if(saved.value)root.style.setProperty('zoom',saved.value,saved.priority);"
                + "else root.style.removeProperty('zoom');"
                + "delete window.__omarchyPageZoom;"
                + "}else root.style.setProperty('zoom',String(saved.base*factor),'important');"
                + "})()",
            null,
        )
    }

    fun setZoom(value: Double) {
        if (!value.isFinite()) return
        zoom = value.coerceIn(0.25, 5.0)
        applyZoom()
    }

    fun layout(body: JSONObject) {
        val visible = body.optBoolean("visible")
        // Release focus before GONE clears ownership, otherwise the shell can look focused in
        // JavaScript while Android still has no input target.
        if (!visible) updateFocus(false)
        web.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            closeFind()
            return
        }
        val rect = body.optJSONArray("rect") ?: return
        val viewport = body.optDouble("viewport", 0.0)
        if (rect.length() != 4 || viewport <= 0) return
        val scale = (host.root.width / viewport).toFloat()
        val width = (rect.optDouble(2) * scale).toInt().coerceAtLeast(1)
        val height = (rect.optDouble(3) * scale).toInt().coerceAtLeast(1)
        if (width > host.root.width * 4 || height > host.root.height * 4) return
        val params = web.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            web.layoutParams = params
        }
        web.translationX = (rect.optDouble(0) * scale).toFloat()
        web.translationY = (rect.optDouble(1) * scale).toFloat()
        web.alpha = body.optDouble("opacity", 1.0).toFloat()
        radius = (body.optDouble("radius", 0.0) * scale).toFloat()
        roundedTop = body.optBoolean("roundedTop")
        if (body.has("controlsHidden")) hiddenControls = body.optBoolean("controlsHidden")
        web.invalidateOutline()
        updateFocus(body.optBoolean("focused"))
    }

    fun updateFocus(focused: Boolean) {
        val gaining = focused && !requestedFocus
        requestedFocus = focused
        when {
            !focused -> if (web.hasFocus()) host.focusShell()
            // Layout animation frames must not steal focus back from page controls or dialogs.
            gaining && host.hardwareKeyboard -> web.requestFocus()
        }
    }

    fun applyDark(enabled: Boolean) {
        dark = enabled
        // DarkReader is bundled with the shell, so inject it on first use and toggle after.
        web.evaluateJavascript("typeof window.DarkReader !== 'undefined'") { available ->
            if (available == "true") {
                host.pageDark(id)
            } else {
                web.evaluateJavascript(host.assetText("Web/vendor/darkreader/darkreader.js")) {
                    host.pageDark(id)
                }
            }
        }
    }

    fun openFind() {
        if (web.visibility != View.VISIBLE || findDialog != null) return
        val content = LinearLayout(host)
        content.orientation = LinearLayout.VERTICAL
        val padding = (20 * host.resources.displayMetrics.density).toInt()
        content.setPadding(padding, padding / 2, padding, 0)
        val query = EditText(host)
        query.isSingleLine = true
        query.hint = "Find in page"
        query.contentDescription = "Find in page"
        query.setText(findQuery)
        val count = TextView(host)
        count.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(query)
        content.addView(count)
        web.setFindListener { index, total, done ->
            if (done) {
                count.text = if (total == 0) "No matches" else "${index + 1} of $total"
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, length: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, length: Int) {
                findQuery = text.toString()
                web.findAllAsync(findQuery)
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        findDialog = AlertDialog.Builder(host)
            .setTitle("Find in page")
            .setView(content)
            .setNegativeButton("Previous", null)
            .setNeutralButton("Next", null)
            .setPositiveButton("Done", null)
            .create()
        findDialog?.setOnDismissListener {
            web.clearMatches()
            web.setFindListener(null)
            findDialog = null
        }
        findDialog?.setOnShowListener { dialog ->
            val buttons = (dialog as AlertDialog)
            buttons.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { _ -> web.findNext(false) }
            buttons.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { _ -> web.findNext(true) }
            query.requestFocus()
            query.selectAll()
            web.findAllAsync(findQuery)
        }
        findDialog?.show()
    }

    fun findNext(backwards: Boolean) {
        if (findDialog == null) openFind() else web.findNext(!backwards)
    }

    fun closeFind(): Boolean {
        val dialog = findDialog ?: return false
        dialog.dismiss()
        return true
    }

    fun destroy() {
        closeFind()
        host.root.removeView(web)
        web.destroy()
    }

    private fun emit(data: JSONObject) {
        if (id != "browser") data.put("appID", id)
        host.evaluate(
            "window.dispatchEvent(new CustomEvent('host-browser-state',{detail:$data}));",
        )
    }

    private fun publish() {
        emit(
            json(
                "url" to web.url,
                "title" to web.title,
                "loading" to loading,
                "back" to web.canGoBack(),
                "forward" to web.canGoForward(),
            ),
        )
    }

    fun snapshot() {
        if (web.width < 1 || web.height < 1 || web.visibility != View.VISIBLE) return
        val image = Bitmap.createBitmap(
            minOf(web.width, 600),
            minOf(web.height, 900),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(image)
        canvas.scale(
            image.width.toFloat() / web.width,
            image.height.toFloat() / web.height,
        )
        web.draw(canvas)
        val bytes = java.io.ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 65, bytes)
        image.recycle()
        emit(
            json(
                "preview" to "data:image/jpeg;base64," +
                    android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP),
            ),
        )
    }
}
