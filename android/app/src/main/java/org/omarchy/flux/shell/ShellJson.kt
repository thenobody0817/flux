package org.omarchy.flux.shell

import org.json.JSONObject

/**
 * The shell's bridge speaks `org.json`, so the port keeps JSON building here instead of
 * spreading [JSONObject] construction through the device channels.
 */

/** An object from alternating keys and values: `json("ok" to true, "id" to 7)`. */
fun json(vararg values: Pair<String, Any?>): JSONObject {
    val result = JSONObject()
    for ((key, value) in values) result.put(key, value ?: JSONObject.NULL)
    return result
}

/** An object from text, or an empty one. The shell's payloads are untrusted. */
fun jsonOf(text: String?): JSONObject = try {
    JSONObject(text ?: "{}")
} catch (_: Exception) {
    JSONObject()
}
