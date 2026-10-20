package com.mahudesh.tvremote

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mahudesh.tvremote.data.AppShortcut
import com.mahudesh.tvremote.data.DefaultMacros
import com.mahudesh.tvremote.data.Macro
import com.mahudesh.tvremote.data.SavedTv
import com.mahudesh.tvremote.tv.FoundTv
import com.mahudesh.tvremote.tv.Keys
import com.mahudesh.tvremote.tv.Link
import com.mahudesh.tvremote.ui.components.HapticsSwitch
import com.mahudesh.tvremote.ui.screens.AddTvActions
import com.mahudesh.tvremote.ui.screens.AddTvScreen
import com.mahudesh.tvremote.ui.screens.PermissionDialog
import com.mahudesh.tvremote.ui.screens.ProActions
import com.mahudesh.tvremote.ui.screens.ProToolsScreen
import com.mahudesh.tvremote.ui.screens.RemoteActions
import com.mahudesh.tvremote.ui.screens.RemoteScreen
import com.mahudesh.tvremote.ui.screens.SettingsActions
import com.mahudesh.tvremote.ui.screens.SettingsScreen
import com.mahudesh.tvremote.ui.screens.UpdatePrompt
import com.mahudesh.tvremote.ui.theme.TvRemoteTheme
import kotlinx.coroutines.launch

private enum class Screen { Remote, AddTv, Settings, Pro }

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

class MainActivity : ComponentActivity() {
    private val controller get() = (application as TvRemoteApp).controller
    private val updater get() = (application as TvRemoteApp).updater

    /** Android 17+ asks the user before an app may reach devices on the local network. */
    private var localNetworkOk by mutableStateOf(true)
    private var showPermissionHelp by mutableStateOf(false)
    /** Phone-side speech recognition for the Voice button. */
    private var onHeard: ((String) -> Unit)? = null
    private val listenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) onHeard?.invoke(said)
        onHeard = null
    }

    private val voice by lazy { com.mahudesh.tvremote.data.Voice(this) }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = onHeard ?: return@registerForActivityResult
        if (granted) voice.listen { said -> onHeard = null; cb(said) }
        else { onHeard = null; android.widget.Toast.makeText(this, "Allow the microphone to use voice", android.widget.Toast.LENGTH_SHORT).show() }
    }

    /**
     * Voice button. 1) the phone's voice prompt (Google app etc.), 2) our own listener on any
     * speech engine incl. Android 12+ on-device, 3) nothing on the phone: the TV's voice search.
     */
    private fun listen(onText: (String) -> Unit) {
        onHeard = onText
        voice.promptIntent()?.let { intent ->
            try { listenLauncher.launch(intent); return } catch (_: android.content.ActivityNotFoundException) { }
        }
        if (voice.canListenInApp()) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                voice.listen { said -> onHeard = null; onText(said) }
            } else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        onHeard = null
        controller.key(Keys.SEARCH)
        android.widget.Toast.makeText(this, "This phone has no voice typing, so the TV's own voice search opened", android.widget.Toast.LENGTH_LONG).show()
    }

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkOk = granted
        showPermissionHelp = !granted
        if (granted) controller.connect(force = true)
    }

    private fun requestLocalNetwork() {
        if (!hasLocalNetwork()) askLocalNetwork.launch(LOCAL_NETWORK)
    }

    private fun openAppSettings() = startActivity(
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null)),
    )

    private fun hasLocalNetwork() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Light status-bar icons (time, battery, signal) over our dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { controller.store.haptics.collect { HapticsSwitch.enabled = it } }
        localNetworkOk = hasLocalNetwork()
        // Ask straight away with Android's own prompt; our dialog only appears if it's refused.
        if (savedInstanceState == null) requestLocalNetwork()

        setContent {
            TvRemoteTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Remote) }
                val link by controller.link.collectAsStateWithLifecycle()
                val status by controller.status.collectAsStateWithLifecycle()
                val pro by controller.adbConnected.collectAsStateWithLifecycle()
                val proState by controller.pro.collectAsStateWithLifecycle()
                val adbVolume by controller.adbVolume.collectAsStateWithLifecycle()
                val macros by controller.store.macros.collectAsStateWithLifecycle(DefaultMacros)
                val tvs by controller.store.tvs.collectAsStateWithLifecycle(emptyList())
                val active by controller.store.activeFingerprint.collectAsStateWithLifecycle(null)
                val haptics by controller.store.haptics.collectAsStateWithLifecycle(true)
                val sleepAt by controller.sleepAt.collectAsStateWithLifecycle()
                val pairing by controller.pairing.collectAsStateWithLifecycle()

                val remoteActions = object : RemoteActions {
                    override fun key(code: Int) = controller.key(code)
                    override fun openApp(app: AppShortcut) = controller.openLink(app.link)
                    override fun runMacro(macro: Macro) { controller.runMacro(macro.steps) }
                    override fun syncText(text: String) = controller.syncText(text)
                    override fun submitText(text: String) { controller.submitText(text) }
                    override fun keyboardOpened() = controller.resetTyping()
                    override fun listen(onText: (String) -> Unit) = this@MainActivity.listen(onText)
                    override fun searchYouTube(query: String) =
                        controller.openLink("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query))
                    override fun onSettings() { screen = Screen.Settings }
                    override suspend fun ping() = controller.ping()
                    override fun reconnect() = controller.connect(force = true)
                    override fun disconnect() = controller.disconnectByUser()
                    override fun forget(tv: SavedTv) { controller.forget(tv) }
                    override fun switchTo(tv: SavedTv) { controller.switchTo(tv) }
                    override fun addTv() { screen = Screen.AddTv }
                    override fun sleepIn(minutes: Int?) = controller.sleepIn(minutes)
                    override fun close() = Unit
                }

                if (showPermissionHelp) {
                    PermissionDialog(
                        onOpenSettings = { showPermissionHelp = false; openAppSettings() },
                        onTryAgain = { showPermissionHelp = false; askLocalNetwork.launch(LOCAL_NETWORK) },
                        onDismiss = { showPermissionHelp = false },
                    )
                }

                AnimatedContent(
                    screen,
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(initialScale = 0.97f)) togetherWith (fadeOut(tween(160)) + scaleOut(targetScale = 1.02f))
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Pro -> ProToolsScreen(proState, adbVolume, object : ProActions {
                            override fun back() { screen = Screen.Settings }
                            override suspend fun screenshot() = controller.adbScreenshot()
                            override suspend fun apps() = controller.adbApps()
                            override suspend fun currentApp() = controller.adbCurrentApp()
                            override fun launch(pkg: String) { controller.adbLaunch(pkg) }
                            override fun forceStop(pkg: String) { controller.adbForceStop(pkg) }
                            override fun setVolume(level: Int) { controller.adbSetVolume(level) }
                            override suspend fun mirror(surface: android.view.Surface, onFrame: () -> Unit) = controller.adbMirror(surface, onFrame)
                            override fun openSettings() { controller.adbOpenSettings() }
                        })
                        Screen.Remote -> RemoteScreen(link, status, pro, macros, tvs, sleepAt, remoteActions)
                        Screen.AddTv -> AddTvScreen(
                            controller.discovery, pairing, tvs.map { it.lastHost }.toSet(),
                            object : AddTvActions {
                                override fun pair(tv: FoundTv) = controller.startPairing(tv)
                                override fun submitCode(code: String) = controller.submitCode(code)
                                override fun cancel() { controller.cancelPairing(); screen = Screen.Remote }
                                override fun done() { controller.pairingSeen(); screen = Screen.Remote }
                            },
                        )
                        Screen.Settings -> SettingsScreen(
                            tvs, active, proOn = tvs.firstOrNull { it.fingerprint == active }?.adbEnabled == true,
                            pro = proState, haptics = haptics, macros = macros,
                            actions = object : SettingsActions {
                                override fun back() { screen = Screen.Remote }
                                override fun addTv() { screen = Screen.AddTv }
                                override fun rename(tv: SavedTv, name: String) { controller.rename(tv, name) }
                                override fun forget(tv: SavedTv) { controller.forget(tv) }
                                override fun setPro(on: Boolean) { controller.enableAdb(on) }
                                override fun retryPro() { controller.retryPro() }
                                override fun openProTools() { screen = Screen.Pro }
                                override fun pairWireless(pairingPort: Int, code: String, connectPort: Int) {
                                    controller.pairWireless(pairingPort, code, connectPort)
                                }
                                override fun setHaptics(on: Boolean) { lifecycleScope.launch { controller.store.setHaptics(on) } }
                                override fun saveMacros(list: List<Macro>) { lifecycleScope.launch { controller.store.saveMacros(list) } }
                            },
                        )
                    }
                }

                // In-app listening (phones without a system voice prompt).
                val voiceState by voice.state.collectAsStateWithLifecycle()
                com.mahudesh.tvremote.ui.screens.VoiceSheet(voiceState, onCancel = { voice.stop(); onHeard = null }, onErrorShown = voice::clearError)

                // A downloaded update from GitHub Releases, over whatever screen is open.
                val update by updater.ready.collectAsStateWithLifecycle()
                update?.let { u ->
                    UpdatePrompt(u, onUpdate = { startActivity(updater.install(u)) }, onLater = updater::dismiss)
                }
            }
        }
    }

    /** The phone's own volume buttons drive the TV while a TV is connected. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (controller.link.value is Link.Connected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { controller.key(Keys.VOLUME_UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { controller.key(Keys.VOLUME_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        localNetworkOk = hasLocalNetwork()
        if (localNetworkOk) { showPermissionHelp = false; controller.onForeground() }
        lifecycleScope.launch { updater.check() }
    }

    override fun onStop() {
        super.onStop()
        voice.stop()
        controller.onBackground()
    }
}
