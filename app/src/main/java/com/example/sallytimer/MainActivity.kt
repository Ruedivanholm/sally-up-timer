package com.example.sallytimer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

private const val PREFS = "sally_step1_prefs"
private const val KEY_AUDIO_URI = "audio_uri"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Mp3PlayerApp() }
    }
}

@Composable
private fun Mp3PlayerApp() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Step1Screen()
        }
    }
}

private class PlayerHolder {
    private var mp: MediaPlayer? = null

    var prepared: Boolean = false
        private set

    var lastError: String? = null
        private set

    var lastEvent: String = "Pick MP3 to begin"
        private set

    fun isPlaying(): Boolean = mp?.isPlaying == true
    fun currentPositionMs(): Long = mp?.currentPosition?.toLong() ?: 0L
    fun durationMs(): Long = mp?.duration?.toLong() ?: 0L

    fun load(context: Context, uri: Uri?) {
        release()
        prepared = false
        lastError = null
        lastEvent = if (uri == null) "Pick MP3 to begin" else "Loading…"

        if (uri == null) return

        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                setOnErrorListener { _, what, extra ->
                    prepared = false
                    lastError = "MediaPlayer error what=$what extra=$extra"
                    lastEvent = "Error"
                    true
                }

                setOnPreparedListener {
                    prepared = true
                    lastError = null
                    lastEvent = "Prepared ✓ (paused at start)"
                    pause()
                    seekTo(0)
                }

                setOnCompletionListener {
                    lastEvent = "Finished"
                }

                setDataSource(context, uri)
                prepareAsync()
            }
        } catch (e: Exception) {
            prepared = false
            lastError = "Load exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
        }
    }

    fun playFromStart(): Boolean {
        val p = mp ?: run {
            lastError = "Start requested but MediaPlayer is null"
            lastEvent = "Error"
            return false
        }
        if (!prepared) {
            lastError = "Start requested but not prepared yet"
            lastEvent = "Error"
            return false
        }
        return try {
            lastError = null
            lastEvent = "Starting…"
            p.seekTo(0)
            p.start()
            lastEvent = "Playing…"
            true
        } catch (e: Exception) {
            lastError = "Start exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
            prepared = false
            false
        }
    }

    fun play(): Boolean {
        val p = mp ?: run {
            lastError = "Resume requested but MediaPlayer is null"
            lastEvent = "Error"
            return false
        }
        if (!prepared) {
            lastError = "Resume requested but not prepared yet"
            lastEvent = "Error"
            return false
        }
        return try {
            lastError = null
            p.start()
            lastEvent = "Playing…"
            true
        } catch (e: Exception) {
            lastError = "Resume exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
            false
        }
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) {
            try {
                p.pause()
                lastEvent = "Paused"
            } catch (e: Exception) {
                lastError = "Pause exception: ${e.javaClass.simpleName}: ${e.message}"
                lastEvent = "Error"
            }
        }
    }

    fun resetToStartPaused() {
        val p = mp ?: run {
            lastError = "Reset requested but MediaPlayer is null"
            lastEvent = "Error"
            return
        }
        if (!prepared) {
            lastError = "Reset requested but not prepared yet"
            lastEvent = "Error"
            return
        }
        try {
            p.seekTo(0)
            if (p.isPlaying) p.pause()
            lastError = null
            lastEvent = "Reset ✓ (paused at start)"
        } catch (e: Exception) {
            lastError = "Reset exception: ${e.javaClass.simpleName}: ${e.message}"
            lastEvent = "Error"
        }
    }

    fun release() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        prepared = false
    }
}

@Composable
private fun Step1Screen() {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val player = remember { PlayerHolder() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var audioUri by remember { mutableStateOf(loadAudioUri(context)) }

    // Debug: show the selected URI as text
    val uriText = audioUri?.toString() ?: "(none)"

    var currentMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    // ✅ More robust on Samsung: GetContent
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            audioUri = uri
            saveAudioUri(context, uri)
        }
    }

    LaunchedEffect(audioUri) {
        player.load(context, audioUri)
        currentMs = 0L
        durationMs = 0L
        playing = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (player.prepared) {
                currentMs = player.currentPositionMs()
                durationMs = max(durationMs, player.durationMs())
                playing = player.isPlaying()
            } else {
                currentMs = 0L
                durationMs = 0L
                playing = false
            }
            delay(100)
        }
    }

    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(inCountdown) {
        if (!inCountdown) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            delay(1000)
        }
        inCountdown = false
        player.playFromStart()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Step 1 — MP3 Playback (Diagnostics)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        Text("Selected URI: $uriText", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Status: ${player.lastEvent}", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (player.lastError != null) {
            Text(
                "Error: ${player.lastError}",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { pickAudio.launch("audio/*") }) {
                Text(if (audioUri == null) "Pick MP3" else "Change MP3")
            }

            OutlinedButton(
                enabled = player.prepared && !inCountdown,
                onClick = { player.resetToStartPaused() }
            ) { Text("Reset") }
        }

        if (inCountdown) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
            }
        }

        Text("Time: ${formatMs(currentMs)} / ${formatMs(durationMs)}", fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = player.prepared && !inCountdown,
                onClick = {
                    if (!playing) {
                        inCountdown = true
                        countdown = 3
                    } else {
                        player.pause()
                    }
                }
            ) { Text(if (!playing) "Start (3-2-1)" else "Pause") }

            OutlinedButton(
                enabled = player.prepared && !inCountdown,
                onClick = { player.play() }
            ) { Text("Resume") }
        }

        Divider()
        Text("Report back:", fontWeight = FontWeight.SemiBold)
        Text("• After picking MP3: what does Selected URI show?")
        Text("• Status after picking MP3?")
        Text("• Status after Start?")
        Text("• Any Error line?")
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = max(0L, ms) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun loadAudioUri(context: Context): Uri? {
    val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUDIO_URI, null)
    return s?.let { Uri.parse(it) }
}

private fun saveAudioUri(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_AUDIO_URI, uri.toString())
        .apply()
}
