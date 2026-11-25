package app.potify.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.ui.PotifyApp
import app.potify.offline.ui.PotifyTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var player: PlayerConnection
    private val sharedLink = mutableStateOf<String?>(null)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDownloads() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        player = PlayerConnection(this)
        if (savedInstanceState == null) readSharedLink(intent)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                Prefs.THEME_DARK -> true
                Prefs.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            PotifyTheme(dark) {
                PotifyApp(vm, player, sharedLink, onQueued = ::afterQueued)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedLink(intent)
    }

    private fun readSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            sharedLink.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    /** Asks once for notification permission (so download progress shows), then starts downloading. */
    private fun afterQueued() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Prefs.askedNotifications(this)
        ) {
            Prefs.setAskedNotifications(this)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startDownloads()
        }
    }
}
