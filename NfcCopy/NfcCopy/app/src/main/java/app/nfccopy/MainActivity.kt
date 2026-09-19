package app.nfccopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.TagTechnology
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/* ══════════════════════════════════════════════════════════════════════
   NFC Copy — read, save, clone and write NFC tags. Single file on purpose.
   ══════════════════════════════════════════════════════════════════════ */

// ───────────────────────────── Model ─────────────────────────────

enum class Kind { ULTRALIGHT, CLASSIC, NDEF, INFO }

enum class Verdict(val title: String, val sub: String) {
    FULL("Full copy possible", "Everything on this tag can be written to another one."),
    PARTIAL("Partial copy only", "Only part of this tag can be copied."),
    NONE("Can't be copied", "This tag can't be duplicated."),
    BLANK("Blank tag", "Empty and ready to be written.")
}

/** Everything we know about a scanned tag. Notes starting with "!" are warnings. */
class Dump(
    val uid: ByteArray,
    val type: String,
    val kind: Kind,
    val verdict: Verdict,
    val size: String = "",
    val notes: List<String> = emptyList(),
    val mem: List<ByteArray?> = emptyList(),                       // pages (Ultralight) or blocks (Classic)
    val keys: List<Pair<ByteArray?, ByteArray?>> = emptyList(),    // Classic: (keyA, keyB) per sector
    val ndef: ByteArray? = null,
    val userEnd: Int = 0,                                          // Ultralight: last user page
    val time: Long = System.currentTimeMillis(),
) {
    fun same(o: Dump) = uid.contentEquals(o.uid) && kind == o.kind && ndef.contentEquals(o.ndef) &&
        mem.size == o.mem.size && mem.indices.all { mem[it].contentEquals(o.mem[it]) }

    fun toJson() = JSONObject().apply {
        put("uid", uid.hex()); put("type", type); put("kind", kind.name); put("v", verdict.name)
        put("size", size); put("notes", JSONArray(notes)); put("ue", userEnd); put("t", time)
        put("mem", JSONArray(mem.map { it?.hex() ?: "" }))
        put("kA", JSONArray(keys.map { it.first?.hex() ?: "" }))
        put("kB", JSONArray(keys.map { it.second?.hex() ?: "" }))
        put("ndef", ndef?.hex() ?: "")
    }

    fun text(): String = buildString {
        appendLine("$type, UID ${uid.hex(":")}")
        ndefSummary(ndef)?.let { appendLine("Content: ${it.first}") }
        val label = if (kind == Kind.CLASSIC) "Block" else "Page"
        mem.forEachIndexed { i, b -> appendLine("$label %03d: %s".format(i, b?.hex(" ") ?: "?? unreadable")) }
        if (kind == Kind.NDEF && ndef != null) appendLine("NDEF: ${ndef.hex(" ")}")
    }

    companion object {
        fun fromJson(o: JSONObject): Dump {
            fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
            val kA = list("kA"); val kB = list("kB")
            return Dump(
                o.getString("uid").unhex(), o.getString("type"),
                Kind.valueOf(o.getString("kind")), Verdict.valueOf(o.getString("v")),
                o.getString("size"), list("notes"),
                list("mem").map { it.hexOrNull() },
                kA.indices.map { kA[it].hexOrNull() to kB[it].hexOrNull() },
                o.getString("ndef").hexOrNull(), o.getInt("ue"), o.getLong("t"),
            )
        }
    }
}

class Outcome(val level: Int, val title: String, val detail: String) // 0 ok, 1 partial, 2 failed

sealed interface Ui {
    data object Idle : Ui
    data class Busy(val label: String) : Ui
    class Result(val dump: Dump) : Ui
    class Armed(val dump: Dump) : Ui
    class Done(val out: Outcome, val dump: Dump) : Ui
}

class Model(private val prefs: SharedPreferences) {
    var ui by mutableStateOf<Ui>(Ui.Idle)
    var step by mutableStateOf("")
    val saved = mutableStateListOf<Dump>()

    init {
        runCatching {
            val a = JSONArray(prefs.getString("saved", "[]"))
            for (i in 0 until a.length()) saved.add(Dump.fromJson(a.getJSONObject(i)))
        }
    }

    fun remember(d: Dump) {
        if (d.kind == Kind.INFO || d.verdict == Verdict.NONE || d.verdict == Verdict.BLANK) return
        saved.removeAll { it.same(d) }
        saved.add(0, d)
        while (saved.size > 30) saved.removeAt(saved.lastIndex)
        persist()
    }

    fun delete(d: Dump) { saved.remove(d); persist() }

    private fun persist() =
        prefs.edit().putString("saved", JSONArray(saved.map { it.toJson() }).toString()).apply()
}

// ───────────────────────────── Helpers ─────────────────────────────

private fun ByteArray.hex(sep: String = "") = joinToString(sep) { "%02X".format(it) }
private fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun String.hexOrNull() = if (isEmpty()) null else unhex()

private fun reconnect(t: TagTechnology) {
    runCatching { t.close() }
    runCatching { t.connect() }
}

/** First record of an NDEF message as readable text; second = true when it is an openable link. */
private fun ndefSummary(b: ByteArray?): Pair<String, Boolean>? {
    if (b == null) return null
    return try {
        val recs = NdefMessage(b).records
        val r = recs.firstOrNull() ?: return null
        val uri = if (r.tnf == NdefRecord.TNF_WELL_KNOWN || r.tnf == NdefRecord.TNF_ABSOLUTE_URI) r.toUri() else null
        when {
            uri != null -> uri.toString() to true
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && r.payload.isNotEmpty() -> {
                val p = r.payload
                val lang = p[0].toInt() and 0x3F
                val cs = if ((p[0].toInt() and 0x80) == 0) Charsets.UTF_8 else Charsets.UTF_16
                String(p, 1 + lang, p.size - 1 - lang, cs) to false
            }
            else -> "${recs.size} NDEF record(s)" to false
        }
    } catch (e: Exception) {
        null
    }
}

// ───────────────────────────── NFC: reading ─────────────────────────────

/**
 * Growing key dictionary: ships with common default keys, plus any key the user pastes in
 * (e.g. from MCT) or that the app itself discovers while reading a tag. Persisted, so a key
 * learned once (say, a building's door-lock key) is tried automatically on every tag after.
 */
/**
 * Growing key dictionary: ships with ~600 public default/common keys (compiled from mfoc,
 * Proxmark3/RfidResearchGroup, and other published sources — see assets/keys.txt), plus any
 * key the user pastes in (e.g. from MCT) or that the app itself discovers while reading a tag.
 * Discovered/pasted keys are persisted, so a key learned once (say, a building's door-lock key)
 * is tried automatically on every tag after.
 */
object KeyVault {
    private const val PREF = "keys"
    private lateinit var prefs: SharedPreferences
    val keys = mutableStateListOf<ByteArray>()

    fun init(p: SharedPreferences, assets: android.content.res.AssetManager) {
        prefs = p
        val bundled = try {
            assets.open("keys.txt").bufferedReader().useLines { it.map { l -> l.trim() }.filter { l -> l.length == 12 }.toList() }
        } catch (e: IOException) { emptyList() }
        val custom = prefs.getStringSet(PREF, emptySet()) ?: emptySet()
        keys.clear()
        keys.addAll((bundled + custom).distinct().map { it.unhex() })
    }

    /** Remembers a key that worked, so future tags try it too. No-op if already known. */
    fun learn(k: ByteArray) {
        if (keys.any { it.contentEquals(k) }) return
        keys.add(0, k)
        prefs.edit().putStringSet(PREF, prefs.getStringSet(PREF, emptySet())!!.plus(k.hex())).apply()
    }

    /** User-entered key, from pasted text (hex, with or without separators). Returns how many were added. */
    fun addManual(text: String): Int {
        val found = Regex("[0-9A-Fa-f]{12}").findAll(text.replace(Regex("[:\\-\\s]"), "")).map { it.value.uppercase() }.toList()
        var added = 0
        for (h in found) if (keys.none { it.contentEquals(h.unhex()) }) { keys.add(0, h.unhex()); added++ }
        if (added > 0) prefs.edit().putStringSet(PREF, prefs.getStringSet(PREF, emptySet())!!.plus(found)).apply()
        return added
    }
}

/** Rolling debug log for the current/last operation. In-memory only, cleared at the start of each read/write. */
object DebugLog {
    val lines = mutableStateListOf<String>()
    private var t0 = 0L
    fun start(op: String) { lines.clear(); t0 = SystemClock.elapsedRealtime(); add(op) }
    fun add(s: String) { lines.add("+%4dms  %s".format(SystemClock.elapsedRealtime() - t0, s)) }
    fun text() = lines.joinToString("\n")
}

private fun analyse(tag: Tag, step: (String) -> Unit): Dump {
    val uid = tag.id
    return try {
        MifareClassic.get(tag)?.let { readClassic(it, uid, step) }
            ?: MifareUltralight.get(tag)?.let { readUl(it, uid) }
            ?: Ndef.get(tag)?.let { readNdef(it, uid) }
            ?: other(tag, uid)
    } catch (e: Exception) {
        DebugLog.add("read crashed: ${e.message}")
        Dump(uid, "Unknown tag", Kind.INFO, Verdict.NONE, notes = listOf("!Read failed. Hold the tag steady against the phone and try again."))
    }
}

// --- NTAG / MIFARE Ultralight (raw page dump) ---

private class UL(val name: String, val pages: Int, val userEnd: Int, val cfg: Int, val authByte: Int = 3)

private fun ulLayout(m: MifareUltralight): UL {
    val v = try { m.transceive(byteArrayOf(0x60)) } catch (e: IOException) { reconnect(m); null } // GET_VERSION
    if (v != null && v.size >= 8 && v[2].toInt() == 4) when (v[6].toInt() and 0xFF) {
        0x0F -> return UL("NTAG213", 45, 39, 41)
        0x11 -> return UL("NTAG215", 135, 129, 131)
        0x13 -> return UL("NTAG216", 231, 225, 227)
    }
    if (v != null && v.size >= 8 && v[2].toInt() == 3) when (v[6].toInt() and 0xFF) {
        0x0B -> return UL("Ultralight EV1 (48 B)", 21, 15, 17)
        0x0E -> return UL("Ultralight EV1 (128 B)", 41, 35, 37)
    }
    return if (m.type == MifareUltralight.TYPE_ULTRALIGHT_C) UL("Ultralight C", 48, 39, 42, 0)
    else UL(if (v == null) "Ultralight" else "NTAG (unknown model)", 16, 15, -1)
}

private fun ndefFromPages(u: ByteArray): ByteArray? {
    fun b(k: Int) = if (k < u.size) u[k].toInt() and 0xFF else -1
    var i = 0
    while (i < u.size) {
        val t = b(i)
        if (t == 0) { i++; continue }
        if (t == 0xFE || b(i + 1) < 0) return null
        var len = b(i + 1); var off = i + 2
        if (len == 0xFF) { len = (b(i + 2) shl 8) or b(i + 3); off = i + 4 }
        if (len < 0) return null
        if (t == 0x03) return if (len > 0 && off + len <= u.size) u.copyOfRange(off, off + len) else null
        i = off + len
    }
    return null
}

private fun readUl(m: MifareUltralight, uid: ByteArray): Dump {
    m.connect()
    try {
        val l = ulLayout(m)
        val mem = MutableList<ByteArray?>(l.pages) { null }
        var p = 0
        while (p < l.pages) {
            val chunk = try { m.readPages(p) } catch (e: IOException) { reconnect(m); null }
            for (i in 0..3) if (p + i < l.pages) {
                mem[p + i] = if (chunk != null) chunk.copyOfRange(i * 4, i * 4 + 4)
                else try { m.readPages(p + i).copyOfRange(0, 4) } catch (e: IOException) { reconnect(m); null }
            }
            p += 4
        }

        val bad = (4..l.userEnd).filter { mem[it] == null }
        val head = (0..3).all { mem[it] != null }
        val auth0 = if (l.cfg >= 0) mem.getOrNull(l.cfg)?.get(l.authByte)?.toInt()?.and(0xFF) else null
        val protFrom = if (auth0 != null && auth0 < l.pages) auth0 else null
        val lockedBits = (mem[2]?.let { it[2].toInt() != 0 || it[3].toInt() != 0 } == true) ||
            (l.userEnd + 1 < l.pages && mem[l.userEnd + 1]?.let { it[0].toInt() != 0 || it[1].toInt() != 0 || it[2].toInt() != 0 } == true)

        val user = ByteArray((l.userEnd - 3) * 4).also { u -> for (pg in 4..l.userEnd) mem[pg]?.copyInto(u, (pg - 4) * 4) }
        val zero = 0.toByte()
        val emptyTlv = user.size >= 3 && user[0] == 3.toByte() && user[1] == zero && user[2] == 0xFE.toByte() && user.drop(3).all { it == zero }
        val blank = bad.isEmpty() && (user.all { it == zero } || emptyTlv)
        val readable = (l.userEnd - 3) - bad.size

        val warn = mutableListOf<String>(); val info = mutableListOf<String>()
        if (protFrom != null) {
            warn += if (bad.isNotEmpty()) "!Password-protected from page $protFrom. The data is hidden and the password can't be recovered."
            else "!Write-protected by a password from page $protFrom (still readable). The password isn't copied."
        } else if (bad.isNotEmpty()) {
            warn += "!${bad.size} page(s) can't be read. The tag is password-protected or locked."
        }
        if (lockedBits) warn += "!Lock bits are set. This tag is partly read-only and can't be used as a blank target."
        if (l.name.startsWith("NTAG (unknown")) warn += "!Unknown NTAG model. Only the first 12 user pages are handled."
        if (blank) info += "Empty tag. Ready to receive a copy."
        info += "The UID can't be changed. A copy always keeps its own UID."

        val v = when {
            !head || readable <= 0 -> Verdict.NONE
            blank -> Verdict.BLANK
            bad.isNotEmpty() -> Verdict.PARTIAL
            else -> Verdict.FULL
        }
        return Dump(uid, l.name, Kind.ULTRALIGHT, v, "${(l.userEnd - 3) * 4} bytes", warn + info, mem, emptyList(), ndefFromPages(user), l.userEnd)
    } finally {
        runCatching { m.close() }
    }
}

// --- MIFARE Classic (sector dump using common keys) ---

private fun secStart(s: Int) = if (s < 32) s * 4 else 128 + (s - 32) * 16
private fun secLen(s: Int) = if (s < 32) 4 else 16
private fun secCount(blocks: Int) = when { blocks <= 20 -> 5; blocks <= 64 -> 16; blocks <= 128 -> 32; else -> 40 }

private fun MifareClassic.tryAuth(s: Int, k: ByteArray, b: Boolean) =
    try { if (b) authenticateSectorWithKeyB(s, k) else authenticateSectorWithKeyA(s, k) }
    catch (e: IOException) { reconnect(this); false }

/** Unlocks sector [s] with the first working key ([first] tried unconditionally, then the vault until [deadline]). */
private fun MifareClassic.unlock(s: Int, first: ByteArray?, deadline: Long): Pair<ByteArray, Boolean>? {
    first?.let { if (tryAuth(s, it, false)) return it to false; if (tryAuth(s, it, true)) return it to true }
    for (k in KeyVault.keys) {
        if (first != null && k.contentEquals(first)) continue
        if (SystemClock.elapsedRealtime() > deadline) return null
        if (tryAuth(s, k, false)) return k to false
        if (tryAuth(s, k, true)) return k to true
    }
    return null
}

private fun readClassic(m: MifareClassic, uid: ByteArray, step: (String) -> Unit): Dump {
    m.connect()
    try {
        val ns = m.sectorCount
        step("MIFARE Classic detected — $ns sectors. Hold steady, don't lift the tag.")
        val mem = MutableList<ByteArray?>(m.blockCount) { null }
        val keys = MutableList<Pair<ByteArray?, ByteArray?>>(ns) { null to null }
        var last: ByteArray? = null
        var open = 0
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var timedOut = false
        for (s in 0 until ns) {
            step("Reading sector $s of ${ns - 1}…")
            val hit = m.unlock(s, last, deadline)
            if (hit == null) {
                DebugLog.add("sector $s: no matching key" + if (SystemClock.elapsedRealtime() > deadline) " (time budget used up)" else "")
                if (SystemClock.elapsedRealtime() > deadline) timedOut = true
                continue
            }
            last = hit.first
            KeyVault.learn(hit.first)
            DebugLog.add("sector $s: key ${hit.first.hex()} (key ${if (hit.second) "B" else "A"})")
            val first = secStart(s); val len = secLen(s)
            // A block read can drop transiently (tag shifted slightly); retry twice before giving up on it.
            for (b in first until first + len) {
                var v: ByteArray? = null
                repeat(3) { if (v == null) v = try { m.readBlock(b) } catch (e: IOException) { reconnect(m); m.unlock(s, hit.first, deadline); null } }
                mem[b] = v
            }
            val bad = (first until first + len).count { mem[it] == null }
            if (bad > 0) DebugLog.add("sector $s: $bad block(s) unreadable after retries")
            val tr = mem[first + len - 1]
            var ka: ByteArray? = if (!hit.second) hit.first else null
            var kb: ByteArray? = if (hit.second) hit.first else tr?.copyOfRange(10, 16)?.takeIf { x -> x.any { it != 0.toByte() } }
            if (ka == null) ka = KeyVault.keys.firstOrNull { m.tryAuth(s, it, false) }
            if (kb == null) kb = KeyVault.keys.firstOrNull { m.tryAuth(s, it, true) }
            if (tr != null) { // put the real keys back into the trailer so the dump is complete
                val t = tr.copyOf()
                ka?.copyInto(t, 0); kb?.copyInto(t, 10)
                mem[first + len - 1] = t
            }
            keys[s] = ka to kb
            open++
        }

        val hidden = keys.count { (a, b) -> (a != null || b != null) && (a == null || b == null) }
        val warn = mutableListOf<String>(); val info = mutableListOf<String>()
        if (open == 0) warn += "!No known key opens this card. It uses custom keys, so nothing can be read."
        else if (open < ns) warn += "!${ns - open} of $ns sectors use unknown keys. They can't be read or copied."
        if (timedOut) warn += "!Stopped the key search early to stay fast. Add the tag's key manually to unlock the rest."
        if (hidden > 0) warn += "!$hidden sector(s) have hidden keys. Their data is copied, their keys aren't."
        info += "The UID only changes on a 'magic' card (Gen2/CUID). Regular cards keep their own UID."
        info += "Tried ${KeyVault.keys.size} keys from the dictionary."
        DebugLog.add("read done: $open/$ns sectors opened")

        val v = when { open == 0 -> Verdict.NONE; open < ns -> Verdict.PARTIAL; else -> Verdict.FULL }
        val kb = if (m.size >= 1024) "${m.size / 1024} KB" else "${m.size} B"
        return Dump(uid, "MIFARE Classic " + if (m.size >= 1024) "${m.size / 1024}K" else "Mini", Kind.CLASSIC, v, "$kb, $ns sectors", warn + info, mem, keys)
    } finally {
        runCatching { m.close() }
    }
}

// --- Generic NDEF (NFC-V, Type 3/4, ...) ---

private fun readNdef(n: Ndef, uid: ByteArray): Dump {
    val name = when (n.type) {
        Ndef.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
        Ndef.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
        Ndef.NFC_FORUM_TYPE_3 -> "NFC Forum Type 3"
        Ndef.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
        else -> "NDEF tag"
    }
    var bytes: ByteArray? = null
    var writable = true
    var max = 0
    var failed = false
    try {
        n.connect(); writable = n.isWritable; max = n.maxSize; bytes = n.ndefMessage?.toByteArray()
    } catch (e: Exception) {
        failed = true
    } finally {
        runCatching { n.close() }
    }
    val raw = bytes
    val empty = raw == null || runCatching { NdefMessage(raw).records.all { it.tnf == NdefRecord.TNF_EMPTY } }.getOrDefault(true)
    val notes = mutableListOf<String>()
    if (failed) notes += "!Read failed. Hold the tag steady and try again."
    if (!writable) notes += "!This tag is read-only, so it can't be used as a blank target."
    notes += if (empty) "Empty tag. Ready to receive content." else "Only the NDEF content is copied. The UID and any hidden data aren't."
    val v = when { failed -> Verdict.NONE; empty -> Verdict.BLANK; else -> Verdict.PARTIAL }
    return Dump(uid, name, Kind.NDEF, v, "$max bytes", notes, ndef = if (empty) null else bytes)
}

// --- Everything else: explain why it can't be handled ---

private fun other(tag: Tag, uid: ByteArray): Dump {
    val techs = tag.techList.map { it.substringAfterLast('.') }
    val sak = NfcA.get(tag)?.sak?.toInt() ?: -1
    return when {
        sak in listOf(0x08, 0x09, 0x18, 0x88) -> Dump(uid, "MIFARE Classic", Kind.INFO, Verdict.NONE,
            notes = listOf("!This phone's NFC chip can't read MIFARE Classic (typical for Pixel and some other models). Try a phone with an NXP NFC chip."))
        "IsoDep" in techs -> Dump(uid, "Smart card (ISO-DEP)", Kind.INFO, Verdict.NONE,
            notes = listOf("!Bank, transit, ID and DESFire cards use encryption. They can't be cloned."))
        "NdefFormatable" in techs -> Dump(uid, "Blank tag", Kind.INFO, Verdict.BLANK,
            notes = listOf("Empty tag. Scan a tag first, then hold this one to write."))
        "NfcF" in techs -> Dump(uid, "FeliCa", Kind.INFO, Verdict.NONE,
            notes = listOf("!FeliCa cards are protected and can't be cloned."))
        else -> Dump(uid, techs.firstOrNull() ?: "Unknown tag", Kind.INFO, Verdict.NONE,
            notes = listOf("!This tag type can't be copied."))
    }
}

// ───────────────────────────── NFC: writing ─────────────────────────────

private fun writeTo(tag: Tag, d: Dump, step: (String) -> Unit): Outcome = try {
    when (d.kind) {
        Kind.ULTRALIGHT -> MifareUltralight.get(tag)?.let { writeUl(it, d, step) }
            ?: d.ndef?.let { b ->
                writeNdef(tag, b).let { o ->
                    if (o.level == 0) Outcome(1, "Content copied", "Different chip type. Only the NDEF content was copied.") else o
                }
            }
            ?: Outcome(2, "Wrong target", "Use an NTAG or Ultralight tag as the target.")
        Kind.CLASSIC -> MifareClassic.get(tag)?.let { writeClassic(it, d, step) }
            ?: Outcome(2, "Wrong target", "Use a MIFARE Classic card as the target.")
        Kind.NDEF -> d.ndef?.let { writeNdef(tag, it) } ?: Outcome(2, "Nothing to write", "This tag has no content.")
        Kind.INFO -> Outcome(2, "Nothing to write", "This tag has no content.")
    }
} catch (e: Exception) {
    DebugLog.add("write crashed: ${e.message}")
    Outcome(2, "Tag lost", "Hold the tag steady against the phone and try again.")
}

private fun writeUl(t: MifareUltralight, d: Dump, step: (String) -> Unit): Outcome {
    t.connect()
    try {
        val l = ulLayout(t)
        step("Writing to ${l.name}. Don't move the tag until this finishes.")
        var last = 4
        for (p in 4..d.userEnd) if (d.mem[p]?.any { it != 0.toByte() } == true) last = p
        if (last > l.userEnd)
            return Outcome(2, "Target too small", "Needs ${(last - 3) * 4} bytes, ${l.name} holds ${(l.userEnd - 3) * 4}.")

        // Pages 0-3 (UID, lock bytes, OTP) and config/password pages are never written: irreversible or unsafe.
        var written = 0; var rejected = 0; var missing = 0
        for (p in 4..last) {
            val data = d.mem[p]
            if (data == null) { missing++; continue }
            step("Writing page $p of $last…")
            var ok = false
            repeat(3) { if (!ok) { try { t.writePage(p, data); ok = true } catch (e: IOException) { reconnect(t) } } }
            if (ok) written++ else { rejected++; DebugLog.add("page $p rejected after retries") }
            if (written == 0 && rejected >= 2) return Outcome(2, "Write failed", "The tag rejected every write. It is locked or password-protected. Try a blank tag.")
        }

        step("Verifying…")
        var mismatch = 0
        var p = 4
        while (p <= last) {
            val c = try { t.readPages(p) } catch (e: IOException) { reconnect(t); null }
            for (i in 0..3) if (p + i <= last) {
                val exp = d.mem[p + i]
                if (exp != null && (c == null || !c.copyOfRange(i * 4, i * 4 + 4).contentEquals(exp))) mismatch++
            }
            p += 4
        }
        DebugLog.add("write done: $written written, $rejected rejected, $mismatch failed verification")
        return if (rejected == 0 && missing == 0 && mismatch == 0)
            Outcome(0, "Cloned", "${l.name}: ${last - 3} pages written and verified. The UID can't be copied.")
        else
            Outcome(1, "Partly written", "$rejected rejected, $missing unreadable in the source, $mismatch failed verification.")
    } finally {
        runCatching { t.close() }
    }
}

private fun writeClassic(t: MifareClassic, d: Dump, step: (String) -> Unit): Outcome {
    t.connect()
    try {
        if (t.blockCount < d.mem.size)
            return Outcome(2, "Target too small", "The source has ${d.mem.size} blocks, this card only ${t.blockCount}.")
        val ns = secCount(d.mem.size)
        step("Writing $ns sectors. Don't move the tag until this finishes.")
        var okSectors = 0; var uidCopied = false; var keysSkipped = 0
        val failedSectors = mutableListOf<Int>()
        var key: Pair<ByteArray, Boolean>? = null
        val deadline = SystemClock.elapsedRealtime() + 12_000

        // A block write can drop transiently (tag shifted slightly). Retry a few times, reconnecting
        // and re-authenticating in between, before treating the sector as genuinely failed.
        fun write(s: Int, b: Int, data: ByteArray): Boolean {
            repeat(3) { attempt ->
                try { t.writeBlock(b, data); return true }
                catch (e: IOException) {
                    DebugLog.add("sector $s block $b: write failed, retry ${attempt + 1}")
                    reconnect(t); key = t.unlock(s, key?.first, deadline)
                }
            }
            return false
        }

        for (s in 0 until ns) {
            val first = secStart(s); val len = secLen(s)
            val src = d.mem.subList(first, first + len)
            if (src.all { it == null }) continue
            step("Writing sector $s of ${ns - 1}…")
            key = t.unlock(s, key?.first, deadline) ?: run { DebugLog.add("sector $s: auth failed, skipped"); failedSectors += s; continue }
            var bad = 0
            // Block 0 (UID) only succeeds on "magic" cards; a normal card just refuses it.
            if (s == 0) src[0]?.let { if (write(0, 0, it)) uidCopied = true }
            for (i in (if (s == 0) 1 else 0) until len - 1) src[i]?.let { if (!write(s, first + i, it)) bad++ }
            val (ka, kb) = d.keys.getOrNull(s) ?: (null to null)
            val tr = src[len - 1]
            if (tr != null && ka != null && kb != null) { if (!write(s, first + len - 1, tr)) bad++ } else keysSkipped++
            if (bad == 0) okSectors++ else { failedSectors += s; DebugLog.add("sector $s: $bad block(s) failed after retries") }
        }
        DebugLog.add("write done: $okSectors/$ns sectors")

        val detail = buildString {
            append("$okSectors of $ns sectors written. ")
            append(if (uidCopied) "UID copied (magic card). " else "UID unchanged. ")
            if (keysSkipped > 0) append("Keys of $keysSkipped sector(s) weren't copied. ")
            if (failedSectors.isNotEmpty()) {
                append("Sector(s) ${failedSectors.joinToString(", ")} didn't write. ")
                append(
                    if (0 in failedSectors) "Sector 0 holds the UID and manufacturer data — some readers check it, so test the tag on the actual door before trusting this copy."
                    else "Most access cards only use 1–2 sectors for the actual door credential; the rest are blank. Test on the real reader — it will very likely still work if the sector(s) it checks came through."
                )
            }
        }
        return when {
            okSectors == 0 -> Outcome(2, "Write failed", "The card is locked or uses unknown keys. Use a blank card.")
            okSectors == ns && keysSkipped == 0 -> Outcome(0, "Cloned", detail)
            else -> Outcome(1, "Partly cloned", detail)
        }
    } finally {
        runCatching { t.close() }
    }
}

private fun writeNdef(t: Tag, bytes: ByteArray): Outcome {
    val msg = NdefMessage(bytes)
    Ndef.get(t)?.let { n ->
        n.connect()
        try {
            if (!n.isWritable) return Outcome(2, "Target is read-only", "This tag is locked. Use a blank or rewritable tag.")
            if (n.maxSize < bytes.size) return Outcome(2, "Target too small", "Needs ${bytes.size} bytes, the tag holds ${n.maxSize}.")
            n.writeNdefMessage(msg)
        } finally {
            runCatching { n.close() }
        }
        return Outcome(0, "Written", "NDEF content copied. The UID can't be copied.")
    }
    NdefFormatable.get(t)?.let { f ->
        f.connect()
        try { f.format(msg) } finally { runCatching { f.close() } }
        return Outcome(0, "Written", "NDEF content copied. The UID can't be copied.")
    }
    return Outcome(2, "Target not compatible", "This tag can't store NDEF content.")
}

// ───────────────────────────── Activity ─────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var m: Model
    private val nfc: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var nfcOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.light(clear, clear), SystemBarStyle.light(clear, clear))
        m = Model(getSharedPreferences("nfc", MODE_PRIVATE))
        KeyVault.init(getSharedPreferences("keys", MODE_PRIVATE), assets)
        setContent { App(m, nfc != null, nfcOn, ::openNfcSettings, ::openUri, ::copyText) }
    }

    override fun onResume() {
        super.onResume()
        nfcOn = nfc?.isEnabled == true
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        nfc?.enableReaderMode(this, NfcAdapter.ReaderCallback { onTag(it) }, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    /** Runs on the NFC thread. Scanning works from any screen; when armed, the next tag is written. */
    private fun onTag(tag: Tag) {
        if (m.ui is Ui.Busy) return
        val t0 = SystemClock.elapsedRealtime()
        val armed = (m.ui as? Ui.Armed)?.dump
        val step: (String) -> Unit = { s -> m.step = s; DebugLog.add(s) }
        m.step = ""
        m.ui = Ui.Busy(if (armed != null) "Writing…" else "Reading…")
        if (armed != null) {
            DebugLog.start("WRITE ${armed.type} → target")
            val o = writeTo(tag, armed, step)
            settle(t0); m.ui = Ui.Done(o, armed)
        } else {
            DebugLog.start("READ")
            val d = analyse(tag, step)
            settle(t0); m.remember(d); m.ui = Ui.Result(d)
        }
    }

    /** Keeps the spinner visible long enough to feel deliberate instead of flashing. */
    private fun settle(t0: Long) {
        val left = 550 - (SystemClock.elapsedRealtime() - t0)
        if (left > 0) Thread.sleep(left)
    }

    private fun openNfcSettings() = startActivity(Intent(Settings.ACTION_NFC_SETTINGS))

    private fun openUri(s: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s))) }
        catch (e: Exception) { Toast.makeText(this, "Nothing can open this link", Toast.LENGTH_SHORT).show() }
    }

    private fun copyText(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NFC", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

// ───────────────────────────── UI ─────────────────────────────

private val Bg = Color(0xFFF6F7FB)
private val Ink = Color(0xFF12141F)
private val Sub = Color(0xFF6B7080)
private val Brand = Color(0xFF3D5AFE)
private val Good = Color(0xFF12B76A)
private val Warn = Color(0xFFF79009)
private val Bad = Color(0xFFF04438)

private fun Verdict.color() = when (this) {
    Verdict.FULL -> Good
    Verdict.PARTIAL -> Warn
    Verdict.NONE -> Bad
    Verdict.BLANK -> Brand
}

@Composable
fun App(
    m: Model, hasNfc: Boolean, nfcOn: Boolean,
    onSettings: () -> Unit, onOpen: (String) -> Unit, onCopy: (String) -> Unit,
) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, background = Bg, surface = Color.White, onSurface = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            BackHandler(enabled = m.ui !is Ui.Idle) { if (m.ui !is Ui.Busy) m.ui = Ui.Idle }
            AnimatedContent(
                targetState = m.ui,
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "screen",
            ) { ui ->
                when (ui) {
                    is Ui.Idle -> Home(m, hasNfc, nfcOn, onSettings)
                    is Ui.Busy -> Centered {
                        Spinner(96.dp)
                        Spacer(Modifier.height(28.dp))
                        Text(ui.label, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        AnimatedContent(m.step, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "step") { s ->
                            Text(s.ifEmpty { "Keep the tag against the phone…" }, color = Sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                        }
                    }
                    is Ui.Result -> ResultView(ui.dump, m, onOpen, onCopy)
                    is Ui.Armed -> Centered {
                        Spinner(110.dp)
                        Spacer(Modifier.height(28.dp))
                        Text("Hold the target tag", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Cloning ${ui.dump.type}", color = Sub, fontSize = 15.sp)
                        Text(ui.dump.uid.hex(":"), color = Sub, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Once it starts, don't lift or shift the tag until it's done — moving it mid-write is the main cause of a partial copy.",
                            color = Sub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Spacer(Modifier.height(32.dp))
                        Btn("Cancel", false) { m.ui = Ui.Result(ui.dump) }
                    }
                    is Ui.Done -> DoneView(ui, m, onCopy)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        content()
    }
}

@Composable
private fun Home(m: Model, hasNfc: Boolean, nfcOn: Boolean, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("NFC Copy", Modifier.padding(top = 20.dp, bottom = 8.dp), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (!hasNfc) Notice("This device has no NFC.", null) {}
        else if (!nfcOn) Notice("NFC is turned off.", "Turn on", onSettings)

        val empty = m.saved.isEmpty()
        Box(
            if (empty) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(270.dp),
            contentAlignment = Alignment.Center,
        ) { Hero() }

        if (!empty) {
            Text("Saved", Modifier.padding(bottom = 10.dp), color = Sub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(m.saved) { d -> SavedRow(d) { m.ui = Ui.Result(d) } }
            }
        }
    }
}

@Composable
private fun Hero() {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(190.dp)) {
            val u = size.minDimension
            for (k in 0..1) {
                val q = (p + k * 0.5f) % 1f
                drawCircle(Brand.copy(alpha = (1f - q) * 0.22f), radius = u / 2 * (0.58f + 0.42f * q))
            }
            drawCircle(Brand, radius = u * 0.29f)
            val st = Stroke(u * 0.024f, cap = StrokeCap.Round)
            for (i in 1..3) { // contactless waves
                val r = u * (0.05f * i + 0.03f)
                drawArc(Color.White, -45f, 90f, false, Offset(center.x - u * 0.13f - r, center.y - r), Size(2 * r, 2 * r), style = st)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Hold a tag to the back of your phone", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("It reads instantly. No button needed.", color = Sub, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Notice(text: String, action: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(Warn.copy(alpha = 0.12f)).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp)
        if (action != null) TextButton(onClick = onClick) { Text(action, color = Brand, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun SavedRow(d: Dump, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(d.verdict.color(), CircleShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.type, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.uid.hex(":"), color = Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            DateUtils.getRelativeTimeSpanString(d.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
            color = Sub, fontSize = 13.sp,
        )
    }
}

@Composable
private fun ResultView(d: Dump, m: Model, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    val content = ndefSummary(d.ndef)
    val rows = buildList {
        add("Type" to d.type)
        add("UID" to d.uid.hex(":"))
        if (d.size.isNotEmpty()) add("Memory" to d.size)
        if (content != null) add("Content" to content.first)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { m.ui = Ui.Idle }) { Text("Close", color = Sub) }
            Row {
                var showLog by remember { mutableStateOf(false) }
                if (DebugLog.lines.isNotEmpty()) TextButton(onClick = { showLog = true }) { Text("Debug log", color = Sub) }
                if (showLog) DebugLogDialog(onCopy) { showLog = false }
                if (d in m.saved) TextButton(onClick = { m.delete(d); m.ui = Ui.Idle }) { Text("Delete", color = Bad) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VerdictCard(d.verdict)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 6.dp)) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(k, color = Sub, fontSize = 14.sp)
                        Spacer(Modifier.width(16.dp))
                        Text(v, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            d.notes.sortedBy { !it.startsWith("!") }.forEach { n ->
                val warn = n.startsWith("!")
                Row(Modifier.padding(horizontal = 4.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(7.dp).background(if (warn) Warn else Sub.copy(alpha = 0.5f), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(n.removePrefix("!"), color = Ink.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (d.kind != Kind.INFO && d.verdict != Verdict.NONE && d.verdict != Verdict.BLANK)
                Btn("Clone to another tag", true) { m.ui = Ui.Armed(d) }
            if (d.kind == Kind.CLASSIC && d.verdict != Verdict.FULL) {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add keys to unlock more sectors", color = Brand, fontWeight = FontWeight.SemiBold)
                }
                if (open) AddKeyDialog { open = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (content != null && content.second) Btn("Open", false, Modifier.weight(1f)) { onOpen(content.first) }
                if (d.kind != Kind.INFO) Btn("Copy", false, Modifier.weight(1f)) { onCopy(d.text()) }
            }
        }
    }
}

@Composable
private fun DebugLogDialog(onCopy: (String) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Debug log", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Every step of the last read/write, with timing. Useful if something looks wrong and you want to see exactly what happened.",
                    color = Sub, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(DebugLog.text(), color = Ink, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(DebugLog.text()) }) { Text("Copy", color = Brand, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close", color = Sub) } },
    )
}

@Composable
private fun AddKeyDialog(onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add keys", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Paste key(s) copied from another tool (12 hex characters each, one per line).", color = Sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; msg = null },
                    placeholder = { Text("8829DA9DAF76") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(),
                )
                msg?.let { Spacer(Modifier.height(8.dp)); Text(it, color = if (it.startsWith("Added")) Good else Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = KeyVault.addManual(text)
                msg = if (n > 0) "Added $n key(s). Scan the tag again to use them." else "No valid 12-character hex key found."
                if (n > 0) text = ""
            }) { Text("Save", color = Brand, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close", color = Sub) } },
    )
}

@Composable
private fun VerdictCard(v: Verdict) {
    val c = v.color()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.copy(alpha = 0.10f)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(c, when (v) { Verdict.FULL -> 0; Verdict.PARTIAL -> 1; Verdict.NONE -> 2; Verdict.BLANK -> 3 }, 60.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(v.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(v.sub, color = Sub, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun DoneView(ui: Ui.Done, m: Model, onCopy: (String) -> Unit) {
    val o = ui.out
    val c = when (o.level) { 0 -> Good; 1 -> Warn; else -> Bad }
    var showLog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Badge(c, o.level, 104.dp)
        Spacer(Modifier.height(28.dp))
        Text(o.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(o.detail, color = Sub, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(28.dp))
        if (o.level == 0) {
            Btn("Done", true) { m.ui = Ui.Idle }
            Spacer(Modifier.height(10.dp))
            Btn("Write another copy", false) { m.ui = Ui.Armed(ui.dump) }
        } else {
            Btn("Try again", true) { m.ui = Ui.Armed(ui.dump) }
            Spacer(Modifier.height(10.dp))
            Btn("Close", false) { m.ui = Ui.Result(ui.dump) }
        }
        if (DebugLog.lines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { showLog = true }) { Text("View debug log", color = Sub) }
            if (showLog) DebugLogDialog(onCopy) { showLog = false }
        }
    }
}

@Composable
private fun Btn(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Brand else Color(0xFFE8EBFF),
            contentColor = if (primary) Color.White else Brand,
        ),
        elevation = null,
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Sweeping arc spinner. */
@Composable
private fun Spinner(dia: Dp, color: Color = Brand) {
    val t = rememberInfiniteTransition(label = "spin")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "rot")
    val sweep by t.animateFloat(50f, 250f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
    Canvas(Modifier.size(dia)) {
        val w = size.minDimension * 0.085f
        val box = Size(size.width - w, size.height - w)
        val tl = Offset(w / 2, w / 2)
        drawArc(color.copy(alpha = 0.12f), 0f, 360f, false, tl, box, style = Stroke(w))
        rotate(rot) { drawArc(color, 0f, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round)) }
    }
}

/** Round status badge that springs in. mark: 0 check, 1 warning, 2 cross, 3 empty ring. */
@Composable
private fun Badge(c: Color, mark: Int, dia: Dp) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val s by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 260f), label = "badge")
    Canvas(Modifier.size(dia).scale(s)) {
        val w = size.width
        drawCircle(c.copy(alpha = 0.16f))
        drawCircle(c, radius = w * 0.34f)
        val st = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        when (mark) {
            0 -> { p.moveTo(w * .38f, w * .51f); p.lineTo(w * .47f, w * .60f); p.lineTo(w * .63f, w * .42f) }
            1 -> { p.moveTo(w * .5f, w * .36f); p.lineTo(w * .5f, w * .55f) }
            2 -> { p.moveTo(w * .40f, w * .40f); p.lineTo(w * .60f, w * .60f); p.moveTo(w * .60f, w * .40f); p.lineTo(w * .40f, w * .60f) }
        }
        drawPath(p, Color.White, style = st)
        if (mark == 1) drawCircle(Color.White, w * 0.03f, Offset(w * .5f, w * .65f))
        if (mark == 3) drawCircle(Color.White, w * 0.11f, style = st)
    }
}
