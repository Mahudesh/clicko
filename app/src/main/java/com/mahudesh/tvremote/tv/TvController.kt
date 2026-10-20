package com.mahudesh.tvremote.tv

import android.content.Context
import android.os.Build
import com.mahudesh.tvremote.data.MacroStep
import com.mahudesh.tvremote.data.SavedTv
import com.mahudesh.tvremote.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Link {
    data object NoTv : Link
    data class Connecting(val tv: SavedTv) : Link
    data class Connected(val tv: SavedTv) : Link
    data class Offline(val tv: SavedTv, val reason: String) : Link
}

sealed interface Pairing {
    data object Idle : Pairing
    data class Requesting(val tv: FoundTv) : Pairing
    data class EnterCode(val tv: FoundTv, val error: String? = null, val codeLength: Int = 6) : Pairing
    data class Verifying(val tv: FoundTv, val codeLength: Int = 6) : Pairing
    data class Done(val tv: SavedTv) : Pairing
    data class Failed(val tv: FoundTv, val message: String) : Pairing
}

/** App-wide owner of the TV connection: pairing, auto-reconnect and command dispatch. */
class TvController(context: Context) {
    private val app = context.applicationContext
    val store = Store(app)
    val discovery = TvDiscovery(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _link = MutableStateFlow<Link>(Link.NoTv)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _pairing = object {
        private val flow = MutableStateFlow<Pairing>(Pairing.Idle)
        var value: Pairing
            get() = flow.value
            set(v) { android.util.Log.d("TvRemote", "pairing -> $v"); flow.value = v }
        fun asStateFlow() = flow.asStateFlow()
    }
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    private val _adb = MutableStateFlow<AdbClient?>(null)
    val adbConnected: StateFlow<Boolean> = MutableStateFlow(false).also { flag ->
        scope.launch { _adb.collect { flag.value = it != null } }
    }

    /** Declared early because [status] combines it; written by Pro mode. */
    private val proVolumeFlow = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Mute / screen-on read over ADB (Pro mode). */
    private val proAudioFlow = MutableStateFlow<AdbClient.State?>(null)
    /** Our own mute toggle for v1 TVs, which never report mute. */
    private val localMute = MutableStateFlow(false)

    @Volatile private var session: TvLink? = null
    private val sessionFlow = MutableStateFlow<TvLink?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val status: StateFlow<TvStatus> = sessionFlow
        .flatMapLatest { it?.status ?: flowOf(TvStatus()) }
        .combine(proVolumeFlow) { st, pv ->
            // TV-reported volume wins; otherwise Pro mode's ADB reading (old TVs).
            if (st.volume == null && pv != null) st.copy(volume = pv.first, volumeMax = pv.second) else st
        }
        .combine(combine(proAudioFlow, localMute) { a, m -> a to m }) { st, (adbState, mute) ->
            val reports = session?.reportsVolume == true
            st.copy(
                muted = if (reports) st.muted else adbState?.muted ?: mute,
                powered = adbState?.screenOn ?: st.powered,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, TvStatus())

    private var connectJob: Job? = null
    @Volatile private var userDisconnected = false
    private var pairingClient: Pairer? = null
    /** What worked for the TV currently being paired; saved with it so reconnects skip detection. */
    private var pairingMode = TlsMode.AUTO
    private var pairingProtocol = Protocol.V2

    /** One rung of the pairing ladder. */
    private data class Attempt(val protocol: Protocol, val mode: TlsMode, val make: () -> Pairer)

    /**
     * Pairing ladder: v2 (TLS auto) → v2 (TLS 1.2) → v1 legacy JSON. The first rung the TV
     * accepts wins. A TV we've seen before starts at the rung that worked last time.
     */
    private fun openPairing(tv: FoundTv): Pair<Pairer, String?> {
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val ladder = listOf(
            Attempt(Protocol.V2, TlsMode.AUTO) { PairingClient(tv.host, name, TlsMode.AUTO) },
            Attempt(Protocol.V2, TlsMode.TLS12) { PairingClient(tv.host, name, TlsMode.TLS12) },
            Attempt(Protocol.V1, TlsMode.TLS12) { LegacyPairingClient(tv.host, name) },
        )
        val known = rememberedProtocol[tv.host]
        val ordered = if (known == Protocol.V1) ladder.sortedBy { it.protocol != Protocol.V1 } else ladder
        var last: Exception? = null
        for (attempt in ordered) {
            val client = attempt.make()
            try {
                val serverName = client.start()
                pairingProtocol = attempt.protocol
                pairingMode = attempt.mode
                rememberedProtocol[tv.host] = attempt.protocol
                android.util.Log.i("TvRemote", "pairing: ${tv.host} speaks ${attempt.protocol} over ${attempt.mode}")
                return client to serverName
            } catch (e: Exception) {
                client.close()
                last = e
                // An unreachable TV won't get better with another protocol; anything else
                // (hang-up, garbage reply, handshake refusal, wrong format) tries the next rung.
                val unreachable = e is PairingException && e.stage == Stage.CONNECT && e.cause !is javax.net.ssl.SSLException
                if (unreachable) throw e
                android.util.Log.w("TvRemote", "pairing ${attempt.protocol}/${attempt.mode} failed: ${e.message}")
            }
        }
        throw last ?: PairingException("This TV didn't accept any pairing method")
    }

    /** Protocol learned per host during this app run (survives cancel/retry of the pairing screen). */
    private val rememberedProtocol = mutableMapOf<String, Protocol>()

    // ---------- Connection ----------

    @Volatile private var foreground = false
    private var retryJob: Job? = null

    /** App came to the screen: re-check a link that may have died while we were away, or reconnect. */
    fun onForeground() {
        foreground = true
        val l = _link.value
        if (l is Link.Connected) scope.launch {
            if (!TvDiscovery.isAlive(l.tv.lastHost, 2000) && !TvDiscovery.isAlive(l.tv.lastHost, 2000)) {
                session?.close(); session = null; sessionFlow.value = null
                _link.value = Link.Offline(l.tv, "TV is off or not on this Wi-Fi")
                scheduleRetry()
            }
        } else connect()
    }

    fun onBackground() { foreground = false; retryJob?.cancel() }

    /** While the app is open and the TV is offline, keep looking for it (3s, 6s, 10s, then every 15s). */
    private fun scheduleRetry(attempt: Int = 0) {
        if (userDisconnected || !foreground) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(listOf(3_000L, 6_000L, 10_000L).getOrElse(attempt) { 15_000L })
            if (_link.value !is Link.Offline || userDisconnected) return@launch
            connect()
            connectJob?.join()
            if (_link.value is Link.Offline) scheduleRetry(attempt + 1)
        }
    }

    /** Connects to the active saved TV, searching the network if its IP changed. */
    fun connect(force: Boolean = false) {
        if (!force && (userDisconnected || _link.value is Link.Connected || connectJob?.isActive == true)) return
        userDisconnected = false
        connectJob?.cancel()
        connectJob = scope.launch {
            val tv = store.activeTv() ?: run { _link.value = Link.NoTv; return@launch }
            _link.value = Link.Connecting(tv)
            val host = locate(tv)
            if (host == null) {
                _link.value = Link.Offline(tv, "${tv.name} is off or not on this Wi-Fi")
                if (retryJob?.isActive != true) scheduleRetry()
                return@launch
            }
            // Pro mode doesn't depend on the remote protocol: start it as soon as we know where the TV is.
            if (tv.adbEnabled && _adb.value == null && _pro.value !is ProState.Connecting) scope.launch { connectAdb(tv.copy(lastHost = host)) }
            openSession(tv.copy(lastHost = host))
        }
    }

    private suspend fun openSession(tv: SavedTv) {
        try {
            val onClosed: (Throwable?) -> Unit = { _ ->
                session = null
                sessionFlow.value = null
                _link.value = Link.Offline(tv, "TV is off or not on this Wi-Fi")
                localMute.value = false
                // Quietly keep retrying while the app is open; the TV may have just been switched off.
                scheduleRetry()
            }
            val s: TvLink = when (Protocol.of(tv.protocol)) {
                Protocol.V1 -> LegacySession.open(tv.lastHost, onClosed)
                Protocol.V2 -> {
                    val mode = if (tv.tls12) TlsMode.TLS12 else TlsMode.AUTO
                    try {
                        RemoteSession.open(tv.lastHost, mode, onClosed)
                    } catch (e: NotPairedException) {
                        // Some firmwares only accept our certificate over TLS 1.2.
                        if (mode == TlsMode.TLS12) throw e
                        RemoteSession.open(tv.lastHost, TlsMode.TLS12, onClosed).also { store.upsert(tv.copy(tls12 = true)) }
                    }
                }
            }
            session?.close()
            session = s
            sessionFlow.value = s
            store.upsert(tv)
            _link.value = Link.Connected(tv)
            if (tv.adbEnabled && _adb.value == null && _pro.value !is ProState.Connecting) scope.launch { connectAdb(tv) }
        } catch (e: NotPairedException) {
            _link.value = Link.Offline(tv, "${tv.name} forgot this phone. Pair again.")
        } catch (e: Exception) {
            android.util.Log.e("TvRemote", "session failed", e)
            _link.value = Link.Offline(tv, "Couldn't connect: ${e.message ?: "network error"}")
            scheduleRetry()
        }
    }

    /** Last known IP first, then mDNS by name, then a subnet sweep matched by certificate. */
    private suspend fun locate(tv: SavedTv): String? = withContext(Dispatchers.IO) {
        if (TvDiscovery.isOpen(tv.lastHost, RemoteSession.REMOTE_PORT, 800)) return@withContext tv.lastHost
        withTimeoutOrNull(4000) {
            discovery.mdns().firstOrNull { it.name == tv.name }?.host
        }?.let { return@withContext it }
        discovery.sweep().firstOrNull { found ->
            runCatching {
                TlsIdentity.connect(found.host, RemoteSession.REMOTE_PORT, 1500, if (tv.tls12 || tv.protocol == 1) TlsMode.TLS12 else TlsMode.AUTO)
                    .use { it.peerCertificate().fingerprint() }
            }.getOrNull() == tv.fingerprint
        }?.host
    }

    /** User pressed Disconnect: drop the link and stay offline until they reconnect. */
    fun disconnectByUser() {
        val tv = currentTv()
        userDisconnected = true
        disconnect()
        if (tv != null) _link.value = Link.Offline(tv, "Disconnected")
    }

    private fun currentTv(): SavedTv? = when (val l = _link.value) {
        is Link.Connected -> l.tv
        is Link.Connecting -> l.tv
        is Link.Offline -> l.tv
        Link.NoTv -> null
    }

    /** Round-trip time to the TV's remote port, averaged over 3 probes. Null if unreachable. */
    suspend fun ping(): Long? = withContext(Dispatchers.IO) {
        val host = currentTv()?.lastHost ?: return@withContext null
        val samples = (1..3).mapNotNull {
            val start = System.nanoTime()
            if (TvDiscovery.isOpen(host, RemoteSession.REMOTE_PORT, 1500)) (System.nanoTime() - start) / 1_000_000 else null
        }
        if (samples.isEmpty()) null else samples.average().toLong()
    }

    fun disconnect() {
        connectJob?.cancel()
        session?.close(); session = null; sessionFlow.value = null
        dropAdb()
    }

    fun switchTo(tv: SavedTv) = scope.launch {
        userDisconnected = false
        disconnect()
        store.setActive(tv.fingerprint)
        connect(force = true)
    }

    fun forget(tv: SavedTv) = scope.launch {
        if ((_link.value as? Link.Connected)?.tv?.fingerprint == tv.fingerprint) disconnect()
        store.remove(tv.fingerprint)
        if (store.activeTv() == null) _link.value = Link.NoTv else connect(force = true)
    }

    // ---------- Pairing ----------

    fun startPairing(tv: FoundTv) {
        pairingClient?.close()
        _pairing.value = Pairing.Requesting(tv)
        scope.launch {
            try {
                val (client, serverName) = openPairing(tv)
                pairingClient = client
                val named = serverName?.let { if (tv.name == FoundTv.UNNAMED) tv.copy(name = it) else tv } ?: tv
                _pairing.value = Pairing.EnterCode(named, codeLength = client.codeLength)
            } catch (e: Exception) {
                _pairing.value = Pairing.Failed(tv, friendly(e))
            }
        }
    }

    fun submitCode(code: String) {
        val current = _pairing.value as? Pairing.EnterCode ?: return
        val client = pairingClient ?: return
        _pairing.value = Pairing.Verifying(current.tv, current.codeLength)
        scope.launch {
            try {
                client.finish(code)
                client.close()
                pairingClient = null
                // Read the TV's identity on the remote port; that's what we match on later.
                val fp = TlsIdentity.connect(current.tv.host, RemoteSession.REMOTE_PORT, mode = pairingMode).use {
                    it.peerCertificate().fingerprint()
                }
                val saved = SavedTv(
                    fp, current.tv.name, current.tv.host,
                    tls12 = pairingMode == TlsMode.TLS12, protocol = pairingProtocol.wire,
                )
                disconnect()
                store.upsert(saved)
                _pairing.value = Pairing.Done(saved)
                openSession(saved)
            } catch (e: PairingException) {
                // A wrong secret ends the TV's pairing session; start over so a fresh code appears.
                _pairing.value = Pairing.EnterCode(current.tv, e.message, current.codeLength)
                if (e.message?.startsWith("Wrong code") == true) startPairingKeepingError(current.tv, e.message)
            } catch (e: Exception) {
                android.util.Log.e("TvRemote", "pairing failed", e)
                _pairing.value = Pairing.Failed(current.tv, friendly(e))
            }
        }
    }

    private fun startPairingKeepingError(tv: FoundTv, error: String?) = scope.launch {
        runCatching {
            pairingClient?.close()
            val client = openPairing(tv).first
            pairingClient = client
            _pairing.value = Pairing.EnterCode(tv, error, client.codeLength)
        }.onFailure { _pairing.value = Pairing.Failed(tv, friendly(it)) }
    }

    /** Leaves the success state once the UI has played its animation. */
    fun pairingSeen() { if (_pairing.value is Pairing.Done) _pairing.value = Pairing.Idle }

    fun rename(tv: SavedTv, name: String) = scope.launch {
        val renamed = tv.copy(name = name.trim().ifEmpty { tv.name })
        store.upsert(renamed, makeActive = false)
        when (val l = _link.value) {
            is Link.Connected -> if (l.tv.fingerprint == tv.fingerprint) _link.value = Link.Connected(renamed)
            is Link.Offline -> if (l.tv.fingerprint == tv.fingerprint) _link.value = l.copy(tv = renamed)
            else -> Unit
        }
    }

    fun cancelPairing() {
        pairingClient?.close(); pairingClient = null
        _pairing.value = Pairing.Idle
    }

    private fun friendly(e: Throwable): String = when (e) {
        is PairingException -> listOfNotNull(e.stage?.code, e.message).joinToString(" · ")
        is Net.RoutesFailed -> "Couldn't reach the TV · tried ${e.tried.joinToString(" | ")}"
        is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "TV didn't answer. Make sure it's on and connected to this Wi-Fi."
        else -> e.message ?: "Something went wrong"
    }

    // ---------- Commands ----------

    fun key(code: Int, direction: KeyDirection = KeyDirection.SHORT) {
        if (code == Keys.VOLUME_UP || code == Keys.VOLUME_DOWN || code == Keys.MUTE) refreshProVolumeSoon()
        if (direction != KeyDirection.END_LONG) when (code) {
            Keys.MUTE -> localMute.value = !localMute.value
            Keys.VOLUME_UP, Keys.VOLUME_DOWN -> localMute.value = false // Android unmutes on volume change
        }
        val s = session
        if (s != null) s.key(code, direction)
        else _adb.value?.let { adb -> scope.launch { runCatching { adb.key(code) } } }
    }

    fun openLink(link: String) { session?.openLink(link) }

    /** What we've typed into the TV's field so far, for key-by-key typing. */
    @Volatile private var typed = ""

    /**
     * Mirrors the phone's text box onto the TV live. v2 TVs replace the whole field; v1 TVs
     * (or v2 with a keyboard that ignores IME edits) get the difference as key presses.
     */
    fun syncText(full: String) {
        if (_adb.value != null) return
        val s = session ?: return
        if (s.canSyncTextField) { s.setText(full); typed = full; return }
        val before = typed
        val common = before.commonPrefixWith(full).length
        repeat(before.length - common) { s.key(Keys.DEL) }
        KeyTyper.type(full.substring(common)) { code, dir -> s.key(code, dir) }
        typed = full
    }

    fun resetTyping() { typed = "" }

    /** Final submit: Pro mode types via `input text` (more reliable), then both modes press Enter. */
    fun submitText(full: String) = scope.launch {
        val adb = _adb.value
        if (adb != null) runCatching { adb.type(full) } else syncText(full)
        typed = ""
        delay(150)
        key(Keys.ENTER)
    }

    /** Types [text] into whatever is focused on the TV: ADB in one go, else key by key (works on v1 and v2). */
    private suspend fun type(text: String) {
        val adb = _adb.value
        if (adb != null) { withContext(Dispatchers.IO) { runCatching { adb.type(text) } }; return }
        val s = session ?: return
        KeyTyper.type(text) { code, dir -> s.key(code, dir) }
        delay(text.length * 140L) // let the queued key presses reach the TV before the next step
    }

    fun runMacro(steps: List<MacroStep>) = scope.launch {
        for (step in steps) when (step) {
            is MacroStep.Key -> { key(step.code); delay(180) }
            is MacroStep.Link -> openLink(step.url)
            is MacroStep.Text -> type(step.text)
            is MacroStep.Wait -> delay(step.millis)
        }
    }

    // ---------- Sleep timer ----------

    private val _sleepAt = MutableStateFlow<Long?>(null)
    /** Wall-clock millis when the TV will be switched off, or null. */
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private var sleepJob: Job? = null

    /** Sends Power after [minutes]. Null cancels. Runs while the app process is alive. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAt.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch {
            delay(at - System.currentTimeMillis())
            key(Keys.POWER)
            _sleepAt.value = null
        }
    }

    // ---------- Pro mode (ADB) ----------

    private val _pro = MutableStateFlow<ProState>(ProState.Off)
    val pro: StateFlow<ProState> = _pro.asStateFlow()
    val adbError: StateFlow<String?> = MutableStateFlow<String?>(null).also { flag ->
        scope.launch { _pro.collect { flag.value = (it as? ProState.Failed)?.message } }
    }

    /** Real volume read over ADB (old TVs don't report it on the remote protocol). */
    private val proVolume get() = proVolumeFlow
    /** (level, max) read over ADB, for the Pro tools volume slider. */
    val adbVolume: StateFlow<Pair<Int, Int>?> get() = proVolumeFlow
    private var volumePoll: Job? = null

    fun enableAdb(enabled: Boolean) = scope.launch {
        val tv = currentTv() ?: store.activeTv() ?: return@launch
        val updated = tv.copy(adbEnabled = enabled)
        store.upsert(updated)
        if (enabled) connectAdb(updated) else dropAdb()
        (_link.value as? Link.Connected)?.let { _link.value = Link.Connected(updated) }
    }

    private fun dropAdb() {
        volumePoll?.cancel(); proVolume.value = null; proAudioFlow.value = null
        _adb.value?.close(); _adb.value = null
        _pro.value = ProState.Off
    }

    private suspend fun connectAdb(tv: SavedTv) = withContext(Dispatchers.IO) {
        _pro.value = ProState.Connecting
        runCatching { AdbClient.connect(tv.lastHost, tv.adbPort) }
            .onSuccess { client ->
                _adb.value?.close(); _adb.value = client
                val info = runCatching { client.info() }.getOrNull()
                _pro.value = ProState.Connected(info)
                startVolumePoll(client)
            }
            .onFailure { e ->
                android.util.Log.w("TvRemote", "pro connect failed", e)
                _adb.value = null
                _pro.value = ProState.Failed(
                    when {
                        e.message?.contains("refused", true) == true || e is java.net.ConnectException ->
                            "TV isn't accepting debugging connections on port ${tv.adbPort}. Turn on ADB / Network debugging (or use Wireless debugging below)."
                        e is java.net.SocketTimeoutException || e.message?.contains("timed out", true) == true ->
                            "No answer. Check the TV for an \"Allow debugging?\" prompt and choose Always allow."
                        else -> "Pro mode couldn't connect: ${e.message ?: e.javaClass.simpleName}"
                    },
                )
            }
    }

    private fun startVolumePoll(client: AdbClient) {
        volumePoll?.cancel()
        volumePoll = scope.launch {
            while (true) {
                runCatching { client.state() }.getOrNull()?.let { st ->
                    proAudioFlow.value = st
                    st.volume?.let { proVolume.value = it }
                }
                delay(2500)
            }
        }
    }

    /** Re-reads volume right after a volume key, so the number feels live. */
    private fun refreshProVolumeSoon() {
        val client = _adb.value ?: return
        scope.launch {
            delay(350)
            runCatching { client.state() }.getOrNull()?.let { st -> proAudioFlow.value = st; st.volume?.let { proVolume.value = it } }
        }
    }

    /** Android 11+ Wireless debugging: pair with the code + pairing port, then connect on the connect port. */
    fun pairWireless(pairingPort: Int, code: String, connectPort: Int) = scope.launch {
        val tv = currentTv() ?: store.activeTv() ?: return@launch
        _pro.value = ProState.Connecting
        val paired = runCatching { AdbClient.pair(tv.lastHost, pairingPort, code) }
        if (paired.isFailure) {
            _pro.value = ProState.Failed("Pairing failed: ${paired.exceptionOrNull()?.message ?: "check the code and pairing port"}")
            return@launch
        }
        val updated = tv.copy(adbEnabled = true, adbPort = connectPort)
        store.upsert(updated)
        connectAdb(updated)
    }

    fun retryPro() = scope.launch { (currentTv() ?: store.activeTv())?.let { connectAdb(it) } }

    private suspend fun <T> withAdb(block: (AdbClient) -> T): T? = withContext(Dispatchers.IO) {
        _adb.value?.let { c -> runCatching { block(c) }.onFailure { android.util.Log.w("TvRemote", "pro command failed", it) }.getOrNull() }
    }

    suspend fun adbApps(): List<TvApp> = withAdb { it.launcherApps() }.orEmpty()
    suspend fun adbScreenshot(): ByteArray? = withAdb { it.screenshotPng() }

    /**
     * Streams the TV screen onto [surface] until cancelled, restarting when `screenrecord` hits its
     * time limit. Returns false if the TV produced no video (caller falls back to screenshots).
     */
    /** Stream size that worked on this TV. */
    @Volatile private var mirrorSize: Pair<Int, Int>? = null

    suspend fun adbMirror(surface: android.view.Surface, onFrame: () -> Unit): Boolean {
        val client = _adb.value ?: return false
        // Sharpest first: the TV's exact resolution (1080p / 720p), capped at 1080p because 4K
        // encoders and phone decoders choke; then 1280x720, the size AOSP screenrecord itself
        // falls back to. Decoder and stream always use the same size so nothing gets cropped.
        // Android 5–9 cap a recording at 180 s: we restart.
        val native = withContext(Dispatchers.IO) { runCatching { client.screenSize() }.getOrNull() }
            ?.let { (w, h) -> if (h > 1080) (w * 1080 / h / 16 * 16) to 1080 else w to h }
        val sizes = (listOfNotNull(mirrorSize, native, 1920 to 1080, 1280 to 720)).distinct()
        var shownAny = false
        for ((w, h) in sizes) {
            val mirror = ScreenMirror(surface, w, h)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val stream = withContext(Dispatchers.IO) { runCatching { client.screenStream("${w}x$h") }.getOrNull() } ?: break
                val frames = try {
                    mirror.play(stream, onFrame)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("TvRemote", "mirror failed at ${w}x$h", e); 0
                }
                if (frames == 0) break
                shownAny = true; mirrorSize = w to h
            }
            if (shownAny) return true
        }
        return false
    }
    suspend fun adbCurrentApp(): String? = withAdb { it.currentApp() }
    fun adbLaunch(pkg: String) = scope.launch { withAdb { it.launch(pkg) } }
    fun adbForceStop(pkg: String) = scope.launch { withAdb { it.forceStop(pkg) } }
    fun adbOpenSettings() = scope.launch { withAdb { it.openSettings() } }
    fun adbSetVolume(level: Int) = scope.launch { withAdb { it.setVolume(level) }; refreshProVolumeSoon() }
}

sealed interface ProState {
    data object Off : ProState
    data object Connecting : ProState
    data class Connected(val info: TvInfo?) : ProState
    data class Failed(val message: String) : ProState
}
