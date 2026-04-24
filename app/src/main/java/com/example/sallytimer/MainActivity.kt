package com.example.sallytimer

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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
    private var prepared = false

    fun isPrepared() = prepared
    fun isPlaying() = mp?.isPlaying == true
    fun currentPositionMs(): Long = mp?.currentPosition?.toLong() ?: 0L
    fun durationMs(): Long = mp?.duration?.toLong() ?: 0L

    fun load(context: Context, uri: Uri?, onPrepared: () -> Unit, onComplete: () -> Unit) {
        release()
        prepared = false
        if (uri == null) return

        mp = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnPreparedListener {
                prepared = true
                pause()
                seekTo(0)
                onPrepared()
            }
            setOnCompletionListener {
                onComplete()
            }
            prepareAsync()
        }
    }

    fun playFromStart() {
        mp?.let {
            it.seekTo(0)
            it.start()
        }
    }

    fun play() {
        mp?.start()
    }

    fun pause() {
        if (mp?.isPlaying == true) mp?.pause()
    }

    fun resetToStartPaused() {
        mp?.seekTo(0)
        pause()
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

    // Keep screen awake (gym-friendly)
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var audioUri by remember { mutableStateOf(loadAudioUri(context)) }
    var status by remember { mutableStateOf("") }

    val playerHolder = remember { PlayerHolder() }
    DisposableEffect(Unit) { onDispose { playerHolder.release() } }

    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    var currentMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // File picker
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don’t allow persist; still usually works for this session.
            }
            audioUri = uri
            saveAudioUri(context, uri)
        }
    }

    // Load audio when audioUri changes
    LaunchedEffect(audioUri) {
        prepared = false
        playing = false
        currentMs = 0L
        durationMs = 0L

        playerHolder.load(
            context = context,
            uri = audioUri,
            onPrepared = {
                prepared = true
                durationMs = playerHolder.durationMs()
                status = "Loaded ✓ (paused at start)"
            },
            onComplete = {
                playing = false
                status = "Finished"
            }
        )
        if (audioUri == null) status = "Pick an MP3 to begin"
    }

    // Clock updates
    LaunchedEffect(Unit) {
        while (true) {
            if (prepared) {
                currentMs = playerHolder.currentPositionMs()
                durationMs = max(durationMs, playerHolder.durationMs())
                playing = playerHolder.isPlaying()
            }
            delay(100)
        }
    }

    // Countdown / manual start
    var inCountdown by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(3) }

    LaunchedEffect(inCountdown) {
        if (!inCountdown) return@LaunchedEffect
        for (i in 3 downTo 1) {
            countdown = i
            delay(1000)
        }
        inCountdown = false
        status = "Playing…"
        playerHolder.playFromStart()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Step 1 — MP3 Playback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
                Text(if (audioUri == null) "Pick MP3" else "Change MP3")
            }

            OutlinedButton(
                enabled = prepared,
                onClick = {
                    playerHolder.resetToStartPaused()
                    status = "Reset to start (paused)"
                }
            ) { Text("Reset") }
        }

        // Big countdown display
        if (inCountdown) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(countdown.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black)
            }
        }

        // Time display
        Text(
            "Time: ${formatMs(currentMs)} / ${formatMs(durationMs)}",
            fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = prepared && !inCountdown,
                onClick = {
                    if (!playing) {
                        // Manual Start (3-2-1)
                        inCountdown = true
                        countdown = 3
                        status = "Starting…"
                    } else {
                        playerHolder.pause()
                        status = "Paused"
                    }
                }
            ) { Text(if (!playing) "Start (3-2-1)" else "Pause") }

            OutlinedButton(
                enabled = prepared && !inCountdown,
                onClick = {
                    playerHolder.play()
                    status = "Playing…"
                }
            ) { Text("Resume") }
        }

        Divider()

        Text(
            "Next steps:",
            fontWeight = FontWeight.SemiBold
        )
        Text("• Step 2: Add manual UP/DOWN buttons + save cue timestamps")
        Text("• Step 3: Add auto-detect (mic) as an optional helper")
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
