package com.mcd.tv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates, so new versions don't need Downloader.
 * Checks the latest GitHub release (tag "v0.2.N", where N is the CI run number and the app's versionCode),
 * downloads Jarvis.apk into the app's cache and installs it.
 *  - Android 12+ (Google TV): a session install that asks for no confirmation when Jarvis updates itself
 *    (USER_ACTION_NOT_REQUIRED + UPDATE_PACKAGES_WITHOUT_USER_ACTION). The TV may still ask once, the first
 *    time after a Downloader install; [UpdateReceiver] opens that screen.
 *  - Older Android (Fire TV): the system installer opens and you press Install.
 * Android 8+ asks one time to let Jarvis install apps (Fire TV: Settings > My Fire TV > Developer options
 * > Install unknown apps > Jarvis).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/JFortress0/McD-TV/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    data class Release(val code: Long, val name: String, val apkUrl: String, val bytes: Long)

    /** A newer release than the installed app, or null. */
    var available by mutableStateOf<Release?>(null)
        private set

    /** Progress or error text for the update screen. Blank when idle. */
    var status by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** The update screen opens by itself once per app launch. */
    var promptShown = false

    /** The version code the update screen last opened by itself for (each new version is offered once). */
    var promptedFor = 0L

    private var lastCheck = 0L
    /** Checks running now (an automatic one and a forced one can overlap); [checking] is true while any runs. */
    private var checksRunning = 0
    /** When the APK went to the system installer (0 = not handed over). [busy] stays on until it answers. */
    private var handedOverAt = 0L

    /** True while a check runs (Settings > App updates shows "Checking…"). */
    var checking by mutableStateOf(false)
        private set

    /** Why the last check failed, or blank when it worked. */
    var checkError by mutableStateOf("")
        private set

    /** The newest version on GitHub at the last good check ("0.2.201"), blank before the first one. */
    var latestName by mutableStateOf(runCatching { JSONObject(Prefs.json(CHECK_KEY)).optString("latest") }.getOrDefault(""))
        private set

    /** When the last good check finished (ms), 0 before the first one. Kept on this TV only. */
    var checkedAt by mutableStateOf(runCatching { JSONObject(Prefs.json(CHECK_KEY)).optLong("at") }.getOrDefault(0L))
        private set

    private const val CHECK_KEY = "update_check"

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Looks for a newer release (at most every 6 hours unless [force]). Errors leave [available] as it was. */
    suspend fun check(ctx: Context, force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && (checksRunning > 0 || now - lastCheck < CHECK_EVERY_MS)) return available
        lastCheck = now
        checksRunning++
        checking = true
        return try {
            val o = JSONObject(Http.get(LATEST, mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 15_000))
            val tag = o.optString("tag_name")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return available
            val assets = o.optJSONArray("assets")
            var url = ""
            var bytes = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name") == "Jarvis.apk") { url = a.optString("browser_download_url"); bytes = a.optLong("size") }
            }
            if (url.isBlank()) return available
            available = if (code > installedCode(ctx)) Release(code, tag.removePrefix("v"), url, bytes) else null
            latestName = tag.removePrefix("v")
            checkedAt = System.currentTimeMillis()
            checkError = ""
            if (status.startsWith("Couldn't check")) status = ""
            runCatching { Prefs.putJson(CHECK_KEY, JSONObject().put("latest", latestName).put("at", checkedAt).toString()) }
            available
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (force) {
                status = "Couldn't check for updates. Check the TV's internet and try again."
                checkError = "Check the TV's internet and try again."
                RecentErrors.note("Update check failed: ${e.message ?: "no answer"}")
            }
            available
        } finally {
            checksRunning = (checksRunning - 1).coerceAtLeast(0)
            checking = checksRunning > 0
        }
    }

    /** Android 8+: has the user let Jarvis install apps? */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" switch for Jarvis. False when this TV has no such screen (most Fire TVs). */
    fun openInstallPermission(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Called by [UpdateReceiver] with what the system installer said. */
    fun installResult(ok: Boolean, text: String) {
        status = text
        if (!ok) {
            handedOverAt = 0L
            busy = false
        }
    }

    /**
     * Android 12+: hands [apk] to PackageInstaller and asks for no confirmation (a self-update). The answer comes
     * to [UpdateReceiver]. Throws if the session can't be made; the caller then opens the old installer screen.
     */
    @androidx.annotation.RequiresApi(31)
    private suspend fun sessionInstall(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("Jarvis.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val done = Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName)
                // MUTABLE: the system adds the result (EXTRA_STATUS) to this intent.
                val pending = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Downloads [r] (progress in [status]) and installs it (see the class notes). */
    suspend fun downloadAndInstall(ctx: Context, r: Release) {
        if (busy) return
        busy = true
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, "Jarvis.apk")
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() }
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true // GitHub sends release files from another host
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (code ${c.responseCode})")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.bytes
                    c.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != shown) {
                                    shown = pct
                                    withContext(Dispatchers.Main) { status = if (pct >= 0) "Downloading… $pct%" else "Downloading…" }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val handedOver = try {
                    sessionInstall(ctx, apk)
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false // fall back to the installer screen below
                }
                if (handedOver) {
                    // Install phase: stay busy until the installer answers (installResult), so a second press can't
                    // delete the APK the installer is reading. If no answer comes (the TV's prompt was dismissed),
                    // Update now comes back after INSTALL_WAIT_MS.
                    status = "Installing. Jarvis closes and opens again by itself."
                    val at = System.currentTimeMillis()
                    handedOverAt = at
                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(INSTALL_WAIT_MS)
                        if (handedOverAt == at) {
                            handedOverAt = 0L
                            busy = false
                        }
                    }
                    return
                }
            }
            status = "Opening the installer. Press Install."
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Leaving the update screen stops the download. Say so the next time it opens.
                status = "The download stopped when you left this page. Press Update now to start again."
                throw e
            }
            status = "Update failed: ${e.message ?: "unknown error"}. Try again, or install with Downloader."
            RecentErrors.note(status)
        } finally {
            if (handedOverAt == 0L) busy = false
        }
    }

    /** How long Update now stays off after the APK went to the system installer with no answer. */
    private const val INSTALL_WAIT_MS = 3 * 60_000L
}
