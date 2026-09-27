package org.omarchy.flux.shell

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Saves a file the shell sends over `shellFiles`: the shell stages bounded base64 chunks in
 * the app cache, then the user either writes the result to a document or shares it. Nothing
 * is written outside the cache without that choice.
 */
class ShellFiles(private val activity: Activity) {
    private var staged: File? = null
    private var token: String? = null
    private var name = "download"
    private var mime = "application/octet-stream"
    private var expected = -1L
    private var completion: ((JSONObject) -> Unit)? = null
    private var copying = false
    private var choices: AlertDialog? = null

    init {
        // Neither an abandoned save nor a share handed to another app is worth a restart.
        activity.cacheDir.listFiles { _, filename -> filename.startsWith("shell-save-") }
            ?.forEach { it.delete() }
        activity.cacheDir.resolve("shares").listFiles()?.forEach { directory ->
            if (directory.lastModified() > System.currentTimeMillis() - 86400000L) return@forEach
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    fun dispatch(body: JSONObject, reply: (JSONObject) -> Unit) {
        val action = body.optString("action")
        if (action == "begin") {
            begin(body, reply)
            return
        }
        val file = staged ?: throw IllegalArgumentException("This file save is no longer active.")
        if (body.optString("token") != token) {
            throw IllegalArgumentException("This file save is no longer active.")
        }
        if (copying) throw IllegalStateException("The file is being saved.")
        when (action) {
            "append" -> append(file, body, reply)
            "save" -> save(file, reply)
            "cancel" -> {
                cancel()
                reply(json("ok" to true))
            }
            else -> throw IllegalArgumentException("Unknown file action: $action")
        }
    }

    private fun begin(body: JSONObject, reply: (JSONObject) -> Unit) {
        if (staged != null) throw IllegalStateException("A file save is already in progress.")
        val size = body.optLong("size", -1)
        if (size < 0 || size > activity.cacheDir.usableSpace) {
            throw IllegalArgumentException("Not enough device space to save this file.")
        }
        name = body.optString("name", "download").replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "download"
        val declared = body.optString("type", "application/octet-stream")
        mime = if (declared.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))) {
            declared
        } else {
            "application/octet-stream"
        }
        staged = File.createTempFile("shell-save-", ".tmp", activity.cacheDir)
        expected = size
        token = UUID.randomUUID().toString()
        reply(json("token" to token))
    }

    private fun append(file: File, body: JSONObject, reply: (JSONObject) -> Unit) {
        if (completion != null) throw IllegalStateException("The file is already complete.")
        val encoded = body.optString("data")
        if (encoded.length > 262144) throw IllegalArgumentException("File chunk is too large.")
        val chunk = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("File chunk is not valid base64.")
        }
        if (body.optLong("offset", -1) != file.length() || file.length() + chunk.size > expected) {
            throw IllegalArgumentException("File transfer is out of order.")
        }
        FileOutputStream(file, true).use { it.write(chunk) }
        reply(json("ok" to true))
    }

    private fun save(file: File, reply: (JSONObject) -> Unit) {
        if (completion != null) throw IllegalStateException("A save dialog is already open.")
        if (file.length() != expected) throw IllegalArgumentException("File transfer is incomplete.")
        completion = reply
        choices = AlertDialog.Builder(activity)
            .setTitle(name)
            .setItems(arrayOf("Save to device", "Share…")) { _, which ->
                choices = null
                if (which == 0) write() else share()
            }
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun write() {
        try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, name),
                SAVE_REQUEST,
            )
        } catch (_: Exception) {
            finish(json("error" to "No Android file picker is available."))
        }
    }

    private fun share() {
        val file = staged ?: return
        val directory = File(activity.cacheDir, "shares/" + UUID.randomUUID())
        val shared = File(directory, name)
        try {
            if (!directory.mkdirs() || !file.renameTo(shared)) {
                throw IOException("Could not prepare the shared file.")
            }
            val uri = FileProvider.getUriForFile(activity, AUTHORITY, shared)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.setClipData(ClipData.newUri(activity.contentResolver, name, uri))
            activity.startActivity(Intent.createChooser(send, "Share $name"))
            // The recipient may read asynchronously, so keep the granted copy after the
            // handoff and let the next start prune it. This reports handoff, not delivery.
            finish(json("shared" to true))
        } catch (error: Exception) {
            shared.delete()
            directory.delete()
            finish(json("error" to "Could not share the file: ${error.message}"))
        }
    }

    fun result(result: Int, data: Intent?) {
        val file = staged ?: return
        if (completion == null) return
        val destination = data?.data
        if (result != Activity.RESULT_OK || destination == null) {
            finish(json("cancelled" to true))
            return
        }
        copying = true
        Thread({
            val response = try {
                copy(file, destination)
                json("saved" to true)
            } catch (error: Exception) {
                json("error" to "Could not save the file: ${error.message}")
            }
            activity.runOnUiThread { finish(response) }
        }, "shell-document-save").start()
    }

    private fun copy(source: File, destination: Uri) {
        val output = activity.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Destination is unavailable")
        output.use { out ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    out.write(buffer, 0, count)
                }
            }
        }
    }

    fun cancel() {
        // A document write the user already authorized finishes on its own.
        if (!copying) finish(json("cancelled" to true)) else completion = null
    }

    private fun finish(response: JSONObject) {
        choices?.dismiss()
        choices = null
        val callback = completion
        completion = null
        staged?.delete()
        staged = null
        token = null
        copying = false
        callback?.invoke(response)
    }

    companion object {
        const val SAVE_REQUEST = 22
        private const val AUTHORITY = "org.omarchy.flux.shellfiles"
    }
}
