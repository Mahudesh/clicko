package com.mahudesh.tvremote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mahudesh.tvremote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases (no server of our own).
 *
 * 1. Reads `api.github.com/repos/<repo>/releases/latest`: tag `v1.2.0`, notes, the attached APK.
 * 2. If that's newer than this build, downloads the APK in the background.
 * 3. Offers it; the user taps Update and Android's installer takes over (Android never lets a
 *    sideloaded app install silently). A line `mandatory: true` in the notes blocks the app until
 *    the user updates.
 */
class Updater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(app.cacheDir, "updates")

    data class Update(val version: String, val notes: String, val mandatory: Boolean, val apk: File)

    private val _ready = MutableStateFlow<Update?>(null)
    /** A downloaded update waiting for the user, or null. */
    val ready: StateFlow<Update?> = _ready.asStateFlow()

    /** Checks at most every [CHECK_EVERY_MS] (always when [force]); never throws. */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            if (!force && _ready.value == null && now - prefs.getLong("last_check", 0) < CHECK_EVERY_MS) return@runCatching
            prefs.edit().putLong("last_check", now).apply()

            val release = JSONObject(get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
            val version = release.getString("tag_name").removePrefix("v").trim()
            if (!isNewer(version, BuildConfig.VERSION_NAME)) { dir.deleteRecursively(); return@runCatching }

            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@runCatching
            val body = release.optString("body")
            val mandatory = MANDATORY.containsMatchIn(body)
            val notes = body.lines().filterNot { MANDATORY.containsMatchIn(it) }.joinToString("\n").trim()

            val apk = File(dir, "Clicko-$version.apk")
            val size = asset.getLong("size")
            if (!apk.exists() || apk.length() != size) {
                dir.deleteRecursively(); dir.mkdirs()
                val part = File(dir, "download.part")
                open(asset.getString("browser_download_url")).inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                if (part.length() != size) { part.delete(); return@runCatching }
                part.renameTo(apk)
            }
            _ready.value = Update(version, notes, mandatory, apk)
        }.onFailure { android.util.Log.w("Clicko", "update check failed", it) }
    }

    /** Hands the APK to Android's installer, first sending the user to allow installs if needed. */
    fun install(update: Update): Intent =
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", update.apk)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** "Later": hide a non-mandatory update until the next app launch. */
    fun dismiss() { if (_ready.value?.mandatory == false) _ready.value = null }

    private fun open(url: String): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub sends release downloads through a redirect to its file CDN.
        repeat(5) {
            conn.connectTimeout = 10_000; conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Clicko/${BuildConfig.VERSION_NAME}")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399) { conn = URL(conn.getHeaderField("Location")).openConnection() as HttpURLConnection; return@repeat }
            if (code != 200) throw java.io.IOException("HTTP $code for $url")
            return conn
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun get(url: String) = open(url).inputStream.bufferedReader().use { it.readText() }

    companion object {
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private val MANDATORY = Regex("""mandatory\s*:\s*true""", RegexOption.IGNORE_CASE)

        /** "1.10.0" > "1.9.2"; ignores suffixes like "-beta". */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
