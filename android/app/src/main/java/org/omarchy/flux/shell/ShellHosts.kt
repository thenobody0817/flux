package org.omarchy.flux.shell

import android.content.Context
import org.json.JSONObject
import java.net.URI

/**
 * The shell's host directory, kept in the `shell` preferences under `hosts` so that the
 * web shell's own **Manage hosts** sheet is the single editor. Flux's own `shellUrl` setting
 * only seeds [sync], so a host added on the phone is not overwritten on the next start.
 */
class ShellHosts(context: Context) {
    private val prefs = context.getSharedPreferences("shell", Context.MODE_PRIVATE)

    /** The directory as the shell expects it: `{"hosts":[…],"selected":"…","disconnected":false}`. */
    val directory: JSONObject = jsonOf(prefs.getString("hosts", "{\"hosts\":[]}"))

    /** The connected host's `/native/` address, or empty when there is none. */
    var selected: String = directory.optString("selected", "").takeIf { it != "null" } ?: ""
        private set

    /** True when the user disconnected on purpose: show the picker instead of reconnecting. */
    val disconnected: Boolean
        get() = directory.optBoolean("disconnected")

    init {
        if (selected == "null") selected = ""
    }

    /**
     * Offers [configured], the address from the Flux settings, as the host to connect to. A
     * host that is already selected is left alone, so this never fights the shell's own
     * host switching, and an unparseable address is ignored rather than breaking startup.
     */
    fun sync(configured: String) {
        if (configured.isBlank() || disconnected) return
        val url = runCatching { normalize(configured) }.getOrNull() ?: return
        if (url == selected) return
        save(hostOf(configured), configured)
        selected = url
        directory.put("selected", url)
        directory.put("disconnected", false)
        persist()
    }

    /** Adds a host, or replaces one with the same address, and selects it. */
    fun save(name: String, raw: String) {
        val url = normalize(raw)
        val hosts = directory.optJSONArray("hosts")
        val next = org.json.JSONArray()
        if (hosts != null) {
            for (index in 0 until hosts.length()) {
                val host = hosts.optJSONObject(index) ?: continue
                if (host.optString("id") != url) next.put(host)
            }
        }
        if (next.length() >= 10) throw IllegalArgumentException("You can save up to 10 hosts")
        next.put(json("id" to url, "url" to url, "name" to name.ifBlank { hostOf(url) }))
        directory.put("hosts", next)
        persist()
    }

    fun remove(id: String) {
        val hosts = directory.optJSONArray("hosts")
        val kept = org.json.JSONArray()
        if (hosts != null) {
            for (index in 0 until hosts.length()) {
                val host = hosts.optJSONObject(index) ?: continue
                if (host.optString("id") != id) kept.put(host)
            }
        }
        directory.put("hosts", kept)
        if (id == selected) {
            selected = ""
            directory.put("selected", "")
        }
        persist()
    }

    fun connect(id: String) {
        val hosts = directory.optJSONArray("hosts")
        val known = hosts != null && (0 until hosts.length()).any {
            hosts.optJSONObject(it)?.optString("id") == id
        }
        if (!known) throw IllegalArgumentException("Unknown host")
        selected = normalize(id)
        directory.put("selected", selected)
        directory.put("disconnected", false)
        persist()
    }

    fun disconnect() {
        directory.put("disconnected", true)
        persist()
    }

    private fun persist() {
        prefs.edit().putString("hosts", directory.toString()).apply()
    }

    fun normalize(raw: String) = normalizeShellUrl(raw, org.omarchy.flux.BuildConfig.DEBUG)
}

/**
 * The address of a computer's shell, always ending in `/native/`. The shell only ever loads
 * that path, so anything else in the address is a mistake worth reporting, and the bridge
 * trusts an origin, so nothing that could add a path, a query, or credentials is accepted.
 * Plain HTTP is allowed for the emulator's host aliases, and only in a debug build.
 */
internal fun normalizeShellUrl(raw: String, allowLocal: Boolean): String {
    val uri = parse(raw) ?: throw IllegalArgumentException("Use the host's HTTPS address")
    val local = allowLocal && uri.scheme == "http" && uri.host in LOCAL_HOSTS
    if ((uri.scheme != "https" && !local) || uri.host == null || uri.userInfo != null ||
        uri.query != null || uri.fragment != null
    ) throw IllegalArgumentException("Use the host's HTTPS address")
    if (uri.path !in SHELL_PATHS) {
        throw IllegalArgumentException("Use the host address without a custom path")
    }
    return originOf(uri) + "/native/"
}

/** The scheme and authority, the unit the shell and the bridge both compare against. */
fun origin(raw: String): String {
    val uri = parse(raw) ?: return ""
    if (uri.scheme == null) return ""
    return originOf(uri)
}

fun hostOf(raw: String): String = parse(raw)?.host.orEmpty()

/**
 * `java.net.URI` rather than `android.net.Uri`, because this is the one place where an
 * untrusted string becomes an origin the bridge trusts, and it has to be unit testable.
 */
private fun parse(raw: String): URI? = try {
    URI(if (raw.contains("://")) raw else "https://${raw.trim()}")
} catch (_: Exception) {
    null
}

private fun originOf(uri: URI): String {
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://${uri.host}$port"
}

private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")
private val SHELL_PATHS = setOf("", "/", "/native", "/native/")
