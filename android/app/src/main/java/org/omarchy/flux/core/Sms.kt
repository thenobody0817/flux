package org.omarchy.flux.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf
import org.omarchy.flux.protocol.str

private const val TAG = "FluxSms"

/** The most rows that 1 conversation sweep reads. Newer rows come first. */
private const val MAX_SWEEP = 3000

/** 1 text of a thread, as the content provider stores it. */
data class SmsRow(
    val id: Long,
    val thread: Long,
    val address: String,
    val body: String,
    /** Milliseconds since the epoch, as the provider stores it. */
    val date: Long,
    val type: Int,
    val read: Boolean,
)

/** Maps [m] to the message shape in a kdeconnect.sms.messages packet. */
fun smsMessage(m: SmsRow): Map<String, Any?> = mapOf(
    "_id" to m.id,
    "thread_id" to m.thread,
    "body" to m.body,
    "date" to m.date,
    "type" to m.type,
    "read" to if (m.read) 1 else 0,
    "addresses" to listOf(mapOf("address" to m.address)),
)

/** The recipients of a kdeconnect.sms.request body. It reads the version 2 [addresses] list, then the version 1 [address]. */
fun smsAddresses(p: Packet): List<String> {
    val list = p.array("addresses")?.mapNotNull { (it as? JsonObject)?.str("address") }
    if (!list.isNullOrEmpty()) return list.filter { it.isNotBlank() }
    return listOfNotNull(p.string("address")?.takeIf { it.isNotBlank() })
}

/**
 * The SMS plugin of the phone. It answers the computer's requests for the
 * conversation list and for 1 thread, and sends a text that the computer
 * asks for. The content provider and SmsManager need READ_SMS and SEND_SMS.
 */
object Sms {
    fun onPacket(core: FluxCore, d: Device, p: Packet) {
        when (p.type) {
            Types.SMS_REQUEST_CONVERSATIONS -> core.io.execute {
                val rows = read(core.app) { readConversations(it) }
                core.locked { d.send(packet(rows)) }
            }
            Types.SMS_REQUEST_CONVERSATION -> {
                val thread = p.long("threadID") ?: return
                val limit = (p.int("numberToRequest") ?: 100).coerceIn(1, 1000)
                core.io.execute {
                    val rows = read(core.app) { readThread(it, thread, limit) }
                    core.locked { d.send(packet(rows)) }
                }
            }
            Types.SMS_REQUEST -> core.io.execute { send(core.app, p) }
        }
    }

    /** Sends 1 kdeconnect.sms.messages packet with the latest message of each thread. */
    private fun packet(rows: List<SmsRow>) =
        Packet(Types.SMS_MESSAGES, bodyOf("version" to 1, "messages" to rows.map(::smsMessage)))

    /** Runs 1 provider read, with the permission failure kept out of the caller. */
    private inline fun read(context: Context, block: (Context) -> List<SmsRow>): List<SmsRow> =
        runCatching { block(context) }
            .onFailure { Log.w(TAG, "cannot read messages", it) }
            .getOrDefault(emptyList())

    private val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.DATE,
        Telephony.Sms.READ,
        Telephony.Sms.TYPE,
        Telephony.Sms.BODY,
    )

    /** The latest message of each thread. */
    private fun readConversations(context: Context): List<SmsRow> {
        val latest = LinkedHashMap<Long, SmsRow>()
        query(context, null, null, "${Telephony.Sms.DATE} DESC", MAX_SWEEP).forEach { row ->
            latest.putIfAbsent(row.thread, row)
        }
        return latest.values.toList()
    }

    /**
     * The newest [limit] messages of 1 thread, oldest first. The query reads
     * the newest rows first, so a long thread shows its latest messages and
     * not just the ones that start it.
     */
    private fun readThread(context: Context, thread: Long, limit: Int): List<SmsRow> =
        query(context, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), "${Telephony.Sms.DATE} DESC", limit).reversed()

    private fun query(context: Context, selection: String?, args: Array<String>?, sort: String, limit: Int): List<SmsRow> {
        if (!Android.hasSms(context)) {
            Log.w(TAG, "no READ_SMS permission")
            return emptyList()
        }
        val rows = ArrayList<SmsRow>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sort)?.use { c ->
            while (rows.size < limit && c.moveToNext()) rows += row(c)
        }
        return rows
    }

    private fun row(c: Cursor): SmsRow = SmsRow(
        id = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)),
        thread = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
        address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
        body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
        date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)),
        type = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
        read = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.READ)) != 0,
    )

    /** Sends a text to each address of a kdeconnect.sms.request. */
    private fun send(context: Context, p: Packet) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no SEND_SMS permission")
            return
        }
        val body = p.string("messageBody")?.takeIf { it.isNotBlank() } ?: return
        val manager = manager(context) ?: return
        for (address in smsAddresses(p)) {
            runCatching {
                val parts = manager.divideMessage(body)
                if (parts.size == 1) {
                    manager.sendTextMessage(address, null, body, null, null)
                } else {
                    manager.sendMultipartTextMessage(address, null, parts, null, null)
                }
            }.onFailure { Log.w(TAG, "cannot send to $address", it) }
        }
    }

    private fun manager(context: Context): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }.getOrNull()
}
