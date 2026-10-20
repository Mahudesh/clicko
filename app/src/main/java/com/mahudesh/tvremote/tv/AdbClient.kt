package com.mahudesh.tvremote.tv

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toOkioPath
import java.io.Closeable
import java.io.File

/**
 * Pro mode: ADB over the network, via Kadb.
 *
 * Two ways in, both shown in Settings:
 *  - Classic "ADB/Network debugging" on port 5555 (most Android TVs incl. Android 9 Mi TVs).
 *  - Android 11+ "Wireless debugging": pair once with the 6-digit code + pairing port, then
 *    connect to the (changing) connect port.
 * The first connection makes the TV show "Allow debugging?"; tick "Always allow".
 */
class AdbClient private constructor(private val kadb: Kadb) : Closeable {

    fun shell(cmd: String): String = kadb.shell(cmd).allOutput.trim()

    fun key(code: Int) = shell("input keyevent $code")

    /** Types a whole string at once (spaces as %s, shell metacharacters escaped). ASCII only. */
    fun type(text: String) {
        val ascii = text.filter { it.code in 32..126 }
        if (ascii.isEmpty()) return
        val escaped = ascii.replace(" ", "%s").replace(Regex("""(["'`$&|;<>()*?!#~\\\[\]{}])"""), "\\\\$1")
        shell("input text \"$escaped\"")
    }

    fun launch(pkg: String) = shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $pkg 1")

    fun openLink(url: String) = shell("am start -a android.intent.action.VIEW -d '${url.replace("'", "")}'")

    /** TV apps that appear on the launcher, sorted by name. */
    fun launcherApps(): List<TvApp> {
        fun query(category: String) = Regex("""packageName=([\w.]+)""")
            .findAll(shell("cmd package query-activities -a android.intent.action.MAIN -c $category"))
            .map { it.groupValues[1] }.toSet()
        // TV apps register LEANBACK_LAUNCHER; some boxes/sideloaded apps only have the normal LAUNCHER.
        val pkgs = (query("android.intent.category.LEANBACK_LAUNCHER") + query("android.intent.category.LAUNCHER"))
            .ifEmpty { shell("pm list packages -3").lines().mapNotNull { it.removePrefix("package:").trim().ifEmpty { null } }.toSet() }
        return pkgs.map { TvApp(it, prettyName(it)) }.sortedBy { it.label.lowercase() }
    }

    fun currentApp(): String? =
        Regex("""mResumedActivity.*? ([\w.]+)/""").find(shell("dumpsys activity activities | grep -m1 mResumedActivity"))?.groupValues?.get(1)

    fun forceStop(pkg: String) = shell("am force-stop $pkg")

    /**
     * Android 10+ has `cmd media_session volume`; Android 9 and older only `media volume`.
     * On old builds `cmd media_session` prints "No shell command implementation" and exits 0,
     * so we check the output rather than chaining with ||.
     */
    @Volatile private var volumeCmd: String? = null

    private fun volumeShell(args: String): String {
        volumeCmd?.let { return shell("$it $args") }
        for (cmd in listOf("cmd media_session volume", "media volume")) {
            val out = shell("$cmd $args 2>&1")
            if (VOLUME_RE.containsMatchIn(out) || "will set volume" in out) { volumeCmd = cmd; return out }
        }
        return ""
    }

    /** Music-stream volume as (level, max), e.g. (21, 50). */
    fun volume(): Pair<Int, Int>? {
        val m = VOLUME_RE.find(volumeShell("--stream 3 --get")) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[3].toInt()
    }

    /** Volume, mute and screen state in one round trip. */
    fun state(): State {
        val out = shell("dumpsys audio | grep -A2 '^- STREAM_MUSIC' | grep Muted; dumpsys power | grep -m1 mWakefulness=")
        return State(
            volume = volume(),
            muted = Regex("""Muted:\s*(true|false)""").find(out)?.groupValues?.get(1)?.toBoolean(),
            screenOn = Regex("""mWakefulness=(\w+)""").find(out)?.groupValues?.get(1)?.let { it == "Awake" },
        )
    }

    data class State(val volume: Pair<Int, Int>?, val muted: Boolean?, val screenOn: Boolean?)

    /**
     * Sets the music volume. Tries the direct "set" command, then checks; some builds ignore it, so
     * we fall back to pressing Volume Up/Down the exact number of steps.
     */
    fun setVolume(level: Int) {
        val before = volume()
        volumeShell("--stream 3 --set $level")
        val after = volume() ?: return
        if (after.first == level || before == null) return
        val steps = level - after.first
        val key = if (steps > 0) 24 else 25
        shell("input keyevent " + List(kotlin.math.abs(steps)) { key }.joinToString(" "))
    }

    fun screenshotPng(): ByteArray = kadb.open("exec:screencap -p").use { it.source.readByteArray() }

    /**
     * Live H.264 stream of the TV screen (`screenrecord`, which the TV encodes in hardware; far
     * faster than PNG screenshots). Ends by itself after ~3 minutes; callers restart it.
     */
    /** The TV's screen size, e.g. (1920, 1080); the override size wins if one is set. */
    fun screenSize(): Pair<Int, Int>? {
        val out = shell("wm size")
        val m = Regex("""Override size: (\d+)x(\d+)""").find(out) ?: Regex("""Physical size: (\d+)x(\d+)""").find(out) ?: return null
        val w = m.groupValues[1].toInt(); val h = m.groupValues[2].toInt()
        return if (w >= h) w to h else h to w
    }

    fun screenStream(size: String?): com.flyfishxu.kadb.stream.AdbStream =
        kadb.open("exec:screenrecord --output-format=h264 ${size?.let { "--size $it " }.orEmpty()}--bit-rate 8000000 -")
    fun openSettings() = shell("am start -a android.settings.SETTINGS")

    fun info(): TvInfo {
        val props = shell("getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk").lines()
        val storage = Regex("""(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+\S+\s+/data""").find(shell("df -h /data | tail -1"))
        return TvInfo(
            maker = props.getOrNull(0).orEmpty(),
            model = props.getOrNull(1).orEmpty(),
            android = props.getOrNull(2).orEmpty(),
            sdk = props.getOrNull(3).orEmpty(),
            storageFree = storage?.groupValues?.get(4),
            storageTotal = storage?.groupValues?.get(2),
        )
    }

    override fun close() = runCatching { kadb.close() }.let { }

    companion object {
        const val PORT = 5555

        /** Keeps our ADB key in app storage, so "Always allow" sticks across launches. */
        fun init(context: Context) {
            val file = File(context.filesDir, "adb/adbkey.pem").apply { parentFile?.mkdirs() }
            KadbCert.configure(OkioFilePrivateKeyStore(file.toOkioPath()))
        }

        /** Connects (blocking until the TV accepts the "Allow debugging?" prompt, up to ~40s). */
        fun connect(host: String, port: Int = PORT): AdbClient {
            val kadb = Kadb.create(host, port, connectTimeout = 5000, socketTimeout = 45_000)
            try {
                kadb.shell("echo ok") // forces the handshake now, not on the first button press
            } catch (e: Exception) {
                runCatching { kadb.close() }
                throw e
            }
            return AdbClient(kadb)
        }

        /** Android 11+ Wireless debugging: pair once with the code shown under "Pair device with pairing code". */
        suspend fun pair(host: String, pairingPort: Int, code: String) = Kadb.pair(host, pairingPort, code.trim(), "Clicko")

        private val VOLUME_RE = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")

        private fun prettyName(pkg: String): String = KNOWN[pkg] ?: pkg.split('.')
            .filterNot { it in setOf("com", "android", "tv", "app", "apps", "in", "org", "net", "google", "livingroom", "leanback") }
            .lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg

        private val KNOWN = mapOf(
            "com.google.android.youtube.tv" to "YouTube",
            "com.netflix.ninja" to "Netflix",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "in.startv.hotstar" to "JioHotstar",
            "com.spotify.tv.android" to "Spotify",
            "com.android.vending" to "Play Store",
            "com.android.tv.settings" to "Settings",
            "com.mitv.tvhome" to "PatchWall",
            "com.google.android.youtube.tvmusic" to "YouTube Music",
            "com.plexapp.android" to "Plex",
            "org.xbmc.kodi" to "Kodi",
            "com.jio.media.ondemand" to "JioCinema",
            "com.graymatrix.did" to "ZEE5",
            "com.sonyliv" to "Sony LIV",
            "com.mxtech.videoplayer.television" to "MX Player",
            "com.google.android.apps.tv.launcherx" to "Google TV",
        )
    }
}

data class TvApp(val pkg: String, val label: String)

data class TvInfo(
    val maker: String,
    val model: String,
    val android: String,
    val sdk: String,
    val storageFree: String?,
    val storageTotal: String?,
)
