package com.mcd.tv.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mcd.tv.data.sync.ProfileSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The profile photos on this TV, one small JPEG per profile, set from the phone (see PhotoCodec.kt).
 * Saved as base64 text in filesDir/profile_photos/<profile>.b64, never in SharedPreferences, so the
 * settings copy (HouseSync, house store) never carries them. Other TVs get them through ProfileSync.
 */
object ProfilePhotos {
    /** Largest width or height accepted. The phone makes 192 px squares. */
    private const val MAX_SIDE = 1024

    @Volatile private var dir: File? = null
    private val lock = Any()
    /** profile -> version ("" = no photo). Filled on first use. */
    private val versions = HashMap<String, String>()
    private val bitmaps = HashMap<String, Pair<String, ImageBitmap>>()
    private val rev = MutableStateFlow(0)
    /** Goes up on every change, so screens that show photos redraw. */
    val revision: StateFlow<Int> get() = rev

    /** Called by Prefs.init. */
    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "profile_photos")
    }

    private fun file(id: String): File? = dir?.let { File(it, "$id.b64") }

    /** [id]'s photo as base64, or null when it has none. */
    fun base64(id: String): String? = runCatching {
        file(id)?.takeIf { it.isFile }?.readText()?.takeIf { PhotoCodec.isValid(it) }
    }.getOrNull()

    /** [id]'s photo version, or "" when it has none. */
    fun version(id: String): String = synchronized(lock) {
        versions.getOrPut(id) { base64(id)?.let(PhotoCodec::version) ?: "" }
    }

    /** [id]'s photo for the profile picker, or null. */
    fun bitmap(id: String): ImageBitmap? {
        val v = version(id)
        if (v.isEmpty()) return null
        synchronized(lock) { bitmaps[id]?.takeIf { it.first == v }?.let { return it.second } }
        val img = base64(id)?.let(::decode) ?: return null
        synchronized(lock) { bitmaps[id] = v to img }
        return img
    }

    private fun decode(b64: String): ImageBitmap? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width > MAX_SIDE || bmp.height > MAX_SIDE) return null
        bmp.asImageBitmap()
    }.getOrNull()

    /**
     * Saves [b64] as [id]'s photo. [share]: also send it to the house's other TVs (false for a copy that came
     * from them). False when it is not a JPEG this TV can show.
     */
    fun set(id: String, b64: String, share: Boolean = true): Boolean {
        if (id !in Prefs.PROFILE_IDS || !PhotoCodec.isValid(b64)) return false
        val v = PhotoCodec.version(b64)
        if (v == version(id)) return true
        val img = decode(b64) ?: return false
        val f = file(id) ?: return false
        val ok = runCatching {
            val folder = f.parentFile ?: error("no folder")
            folder.mkdirs()
            val tmp = File(folder, "${f.name}.tmp")
            tmp.writeText(b64)
            check(tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))) { "rename failed" }
        }.isSuccess
        if (!ok) return false
        synchronized(lock) {
            versions[id] = v
            bitmaps[id] = v to img
        }
        rev.value = rev.value + 1
        if (share) ProfileSync.onProfilePhoto(id, v, PhotoCodec.split(b64))
        return true
    }

    /** Removes [id]'s photo (the picker shows the first letter again). [share]: also on the house's other TVs. */
    fun remove(id: String, share: Boolean = true) {
        if (id !in Prefs.PROFILE_IDS) return
        val had = version(id).isNotEmpty()
        runCatching { file(id)?.delete() }
        synchronized(lock) {
            versions[id] = ""
            bitmaps.remove(id)
        }
        if (had) rev.value = rev.value + 1
        if (share) ProfileSync.onProfilePhoto(id, null, emptyList())
    }
}
